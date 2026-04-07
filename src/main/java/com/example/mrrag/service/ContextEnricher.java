package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import com.example.mrrag.model.EnrichmentSnippet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Enriches ChangeGroups with contextual snippets.
 *
 * <p><b>Design principle:</b> Every strategy starts from a graph query by
 * (filePath, lineNumber) — never from text parsing or token matching.
 *
 * <ol>
 *   <li>For each changed line: look up nodes whose line range covers the line
 *       ({@code byFile} lookup, then range filter).</li>
 *   <li>Follow outgoing edges to find called/used declarations.</li>
 *   <li>Read the declaration from the actual source file using the node’s
 *       {@code filePath / lineStart / lineEnd}.</li>
 * </ol>
 *
 * <p>File paths from GitLab diffs may include a module prefix
 * (e.g. {@code gl-hooks/src/main/java/...}) while the graph stores paths
 * relative to the cloned repo root (e.g. {@code src/main/java/...}).
 * All lookups go through {@link AstGraphService#normalizeFilePath} to
 * strip such prefixes automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextEnricher {

    private final AstGraphService  graphService;
    private final JavaIndexService indexService;

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public List<ChangeGroup> enrich(
            List<ChangeGroup> groups,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        AstGraphService.ProjectGraph sourceGraph = graphService.buildGraph(sourceRepoDir);
        AstGraphService.ProjectGraph targetGraph = graphService.buildGraph(targetRepoDir);

        for (ChangeGroup group : groups) {
            enrichGroup(group, sourceGraph, targetGraph, sourceRepoDir, targetRepoDir);
        }
        return groups;
    }

    // -----------------------------------------------------------------------
    // Per-group dispatch
    // -----------------------------------------------------------------------

    private void enrichGroup(
            ChangeGroup group,
            AstGraphService.ProjectGraph sourceGraph,
            AstGraphService.ProjectGraph targetGraph,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        List<EnrichmentSnippet> snippets = group.enrichments();

        List<ChangedLine> deleted = filterByType(group, ChangedLine.LineType.DELETE);
        List<ChangedLine> all     = group.changedLines();

        strategyEdgesAtChangedLines(all, sourceGraph, sourceRepoDir, snippets);
        strategyDeletedDeclarations(deleted, targetGraph, snippets);
        strategyContainingMethod(all, sourceGraph, sourceRepoDir, snippets);

        trim(snippets);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: nodes at changed lines → outgoing edges → declarations
    // -----------------------------------------------------------------------

    private void strategyEdgesAtChangedLines(
            List<ChangedLine> lines,
            AstGraphService.ProjectGraph graph,
            Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seenDecl = new HashSet<>();

        for (ChangedLine cl : lines) {
            if (cl.type() == ChangedLine.LineType.CONTEXT) continue;
            if (full(snippets)) break;

            String file = graphService.normalizeFilePath(cl.filePath(), graph);
            int    line = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();
            if (line <= 0) continue;

            List<AstGraphService.GraphNode> enclosing = nodesAtLine(graph, file, line);

            for (AstGraphService.GraphNode enc : enclosing) {
                if (full(snippets)) break;

                for (AstGraphService.GraphEdge edge : outgoing(graph, enc)) {
                    if (full(snippets)) break;

                    String targetId = edge.to.id;
                    if (seenDecl.contains(targetId)) continue;
                    seenDecl.add(targetId);

                    AstGraphService.GraphNode target = edge.to;

                    switch (edge.kind) {
                        case CALLS      -> emitMethodDeclaration(target, repoDir, snippets);
                        case USES_FIELD -> emitFieldDeclaration(target, repoDir, snippets);
                        default         -> { /* structural edges — skip */ }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 2: deleted declarations → list usages in target graph
    // -----------------------------------------------------------------------

    private void strategyDeletedDeclarations(
            List<ChangedLine> deleted,
            AstGraphService.ProjectGraph targetGraph,
            List<EnrichmentSnippet> snippets
    ) {
        for (ChangedLine cl : deleted) {
            if (full(snippets)) break;

            String file    = graphService.normalizeFilePath(cl.filePath(), targetGraph);
            int    oldLine = cl.oldLineNumber();
            if (oldLine <= 0) continue;

            List<AstGraphService.GraphNode> declared = nodesAtLine(targetGraph, file, oldLine);

            for (AstGraphService.GraphNode decl : declared) {
                if (full(snippets)) break;

                if (decl.kind != AstGraphService.NodeKind.METHOD
                        && decl.kind != AstGraphService.NodeKind.FIELD) continue;

                List<AstGraphService.GraphEdge> usageEdges = incoming(targetGraph, decl);

                // Filter structural edges (HAS_MEMBER = class→member relationship)
                List<String> usageLines = usageEdges.stream()
                        .filter(e -> e.kind != AstGraphService.EdgeKind.HAS_MEMBER)
                        .limit(10)
                        .map(e -> e.from.filePath + ":" + e.from.lineStart)
                        .distinct()
                        .toList();

                if (usageLines.isEmpty()) continue;

                EnrichmentSnippet.SnippetType type = switch (decl.kind) {
                    case METHOD -> EnrichmentSnippet.SnippetType.METHOD_CALLERS;
                    case FIELD  -> EnrichmentSnippet.SnippetType.FIELD_USAGES;
                    default     -> EnrichmentSnippet.SnippetType.VARIABLE_USAGES;
                };

                snippets.add(new EnrichmentSnippet(
                        type,
                        decl.filePath, decl.lineStart, decl.lineEnd, decl.name,
                        usageLines,
                        describeDeletedUsages(decl, usageLines.size())
                ));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 3: METHOD_BODY — enclosing method of the changed lines
    // -----------------------------------------------------------------------

    private void strategyContainingMethod(
            List<ChangedLine> all,
            AstGraphService.ProjectGraph graph,
            Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;

        ChangedLine first = all.stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .filter(l -> l.lineNumber() > 0 || l.oldLineNumber() > 0)
                .findFirst().orElse(null);
        if (first == null) return;

        String file = graphService.normalizeFilePath(first.filePath(), graph);
        int    line = first.lineNumber() > 0 ? first.lineNumber() : first.oldLineNumber();

        nodesAtLine(graph, file, line).stream()
                .filter(n -> n.kind == AstGraphService.NodeKind.METHOD)
                .min(Comparator.comparingInt(n -> n.lineEnd - n.lineStart))
                .ifPresent(method -> {
                    if (full(snippets)) return;
                    int end = Math.min(method.lineEnd, method.lineStart + maxSnippetLines - 1);
                    List<String> lines = readLines(repoDir, method.filePath, method.lineStart, end);
                    if (lines.isEmpty()) return;
                    snippets.add(new EnrichmentSnippet(
                            EnrichmentSnippet.SnippetType.METHOD_BODY,
                            method.filePath, method.lineStart, end, method.name,
                            lines,
                            "Body of enclosing method '" + method.name + "'"
                    ));
                });
    }

    // -----------------------------------------------------------------------
    // Snippet emitters
    // -----------------------------------------------------------------------

    private void emitMethodDeclaration(
            AstGraphService.GraphNode node, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> sigLines = readUntilOpenBrace(repoDir, node.filePath,
                node.lineStart, node.lineEnd);
        if (sigLines.isEmpty()) return;
        int sigEnd = node.lineStart + sigLines.size() - 1;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                node.filePath, node.lineStart, sigEnd, node.name,
                sigLines,
                "Declaration of method '" + node.name + "' called on changed line"
        ));
    }

    private void emitFieldDeclaration(
            AstGraphService.GraphNode node, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> lines = readLines(repoDir, node.filePath, node.lineStart, node.lineEnd);
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.FIELD_DECLARATION,
                node.filePath, node.lineStart, node.lineEnd, node.name,
                lines,
                "Declaration of field '" + node.name + "' accessed on changed line"
        ));
    }

    // -----------------------------------------------------------------------
    // Graph traversal helpers (replace removed ProjectGraph methods)
    // -----------------------------------------------------------------------

    /**
     * Returns all nodes in {@code graph} whose file matches {@code file}
     * and whose line range [{@code lineStart} … {@code lineEnd}] covers {@code line}.
     */
    private static List<AstGraphService.GraphNode> nodesAtLine(
            AstGraphService.ProjectGraph graph, String file, int line
    ) {
        return graph.byFile.getOrDefault(file, List.of()).stream()
                .filter(n -> n.lineStart <= line && n.lineEnd >= line)
                .toList();
    }

    /** Returns all edges whose {@code from} node is {@code node}. */
    private static List<AstGraphService.GraphEdge> outgoing(
            AstGraphService.ProjectGraph graph, AstGraphService.GraphNode node
    ) {
        return graph.edges.stream()
                .filter(e -> e.from.id.equals(node.id))
                .toList();
    }

    /** Returns all edges whose {@code to} node is {@code node}. */
    private static List<AstGraphService.GraphEdge> incoming(
            AstGraphService.ProjectGraph graph, AstGraphService.GraphNode node
    ) {
        return graph.edges.stream()
                .filter(e -> e.to.id.equals(node.id))
                .toList();
    }

    // -----------------------------------------------------------------------
    // File-reading helpers
    // -----------------------------------------------------------------------

    private List<String> readUntilOpenBrace(
            Path repoDir, String relPath, int from, int maxLine
    ) {
        Path file = repoDir.resolve(relPath);
        if (!Files.exists(file)) return List.of();
        List<String> all;
        try {
            all = Files.readAllLines(file);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = from - 1; i < Math.min(all.size(), maxLine); i++) {
            String l = all.get(i);
            result.add(l.length() > 200 ? l.substring(0, 200) + "..." : l);
            if (l.contains("{")) break;
        }
        return result;
    }

    private List<String> readLines(Path repoDir, String relPath, int from, int to) {
        Path file = repoDir.resolve(relPath);
        if (!Files.exists(file)) return List.of();
        try {
            List<String> all = Files.readAllLines(file);
            int start = Math.max(0, from - 1);
            int end   = Math.min(all.size(), to);
            if (start >= end) return List.of();
            return all.subList(start, end).stream()
                    .map(l -> l.length() > 200 ? l.substring(0, 200) + "..." : l)
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Misc helpers
    // -----------------------------------------------------------------------

    private List<ChangedLine> filterByType(ChangeGroup g, ChangedLine.LineType type) {
        return g.changedLines().stream().filter(l -> l.type() == type).toList();
    }

    private boolean full(List<EnrichmentSnippet> snippets) {
        return snippets.size() >= maxSnippetsPerGroup;
    }

    private void trim(List<EnrichmentSnippet> snippets) {
        while (snippets.size() > maxSnippetsPerGroup) {
            snippets.remove(snippets.size() - 1);
        }
    }

    private String describeDeletedUsages(AstGraphService.GraphNode decl, int count) {
        String kind = switch (decl.kind) {
            case METHOD -> "method";
            case FIELD  -> "field";
            default     -> "symbol";
        };
        return decl.name + " (" + kind + ") is deleted but referenced in " + count + " place(s)";
    }
}
