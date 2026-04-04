package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import com.example.mrrag.model.EnrichmentSnippet;
import com.example.mrrag.model.graph.*;
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
 *   <li>For each changed line: look up all outgoing edges at that line in the
 *       source graph ({@code edgesFrom} where {@code edge.filePath==file} and
 *       {@code edge.line==lineNo}).</li>
 *   <li>Follow the edge to its target node to find the declaration.</li>
 *   <li>Read the declaration from the actual source file using the node's
 *       {@code filePath / startLine / endLine}.</li>
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

    private final AstGraphService graphService;
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
        ProjectGraph sourceGraph = graphService.buildGraph(sourceRepoDir);
        ProjectGraph targetGraph = graphService.buildGraph(targetRepoDir);

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
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        List<EnrichmentSnippet> snippets = group.enrichments();

        List<ChangedLine> added   = filterByType(group, ChangedLine.LineType.ADD);
        List<ChangedLine> deleted = filterByType(group, ChangedLine.LineType.DELETE);
        List<ChangedLine> all     = group.changedLines();

        strategyEdgesAtChangedLines(all, sourceGraph, sourceRepoDir, snippets);
        strategyDeletedDeclarations(deleted, targetGraph, snippets);
        strategyContainingMethod(all, sourceGraph, sourceRepoDir, snippets);

        trim(snippets);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: edges at changed lines → declarations
    // -----------------------------------------------------------------------

    private void strategyEdgesAtChangedLines(
            List<ChangedLine> lines,
            ProjectGraph graph,
            Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seenDecl = new HashSet<>();

        for (ChangedLine cl : lines) {
            if (cl.type() == ChangedLine.LineType.CONTEXT) continue;
            if (full(snippets)) break;

            // Normalize the diff path to the format used inside the graph
            String file = graphService.normalizeFilePath(cl.filePath(), graph);
            int    line = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();
            if (line <= 0) continue;

            List<GraphNode> enclosing = graph.nodesAtLine(file, line);

            for (GraphNode enc : enclosing) {
                if (full(snippets)) break;

                for (GraphEdge edge : graph.outgoing(enc.id())) {
                    if (full(snippets)) break;
                    // Edge must originate from this file at this exact line
                    if (!file.equals(edge.filePath()) || edge.line() != line) continue;

                    String targetId = edge.callee();
                    if (seenDecl.contains(targetId)) continue;
                    seenDecl.add(targetId);

                    GraphNode target = graph.nodes.get(targetId);
                    if (target == null) continue;

                    switch (edge.kind()) {
                        case INVOKES ->
                                emitMethodDeclaration(target, repoDir, snippets);
                        case READS_FIELD, WRITES_FIELD ->
                                emitFieldDeclaration(target, repoDir, snippets);
                        case READS_LOCAL_VAR, WRITES_LOCAL_VAR ->
                                emitVariableDeclaration(target, repoDir, snippets);
                        default -> {}
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
            ProjectGraph targetGraph,
            List<EnrichmentSnippet> snippets
    ) {
        for (ChangedLine cl : deleted) {
            if (full(snippets)) break;

            // Normalize to graph-relative path
            String file    = graphService.normalizeFilePath(cl.filePath(), targetGraph);
            int    oldLine = cl.oldLineNumber();
            if (oldLine <= 0) continue;

            List<GraphNode> declared =
                    targetGraph.byLine.getOrDefault(file + "#" + oldLine, List.of());

            for (GraphNode decl : declared) {
                if (full(snippets)) break;

                List<GraphEdge> usageEdges = targetGraph.incoming(decl.id());
                if (usageEdges.isEmpty()) continue;

                EnrichmentSnippet.SnippetType type = switch (decl.kind()) {
                    case METHOD    -> EnrichmentSnippet.SnippetType.METHOD_CALLERS;
                    case FIELD     -> EnrichmentSnippet.SnippetType.FIELD_USAGES;
                    case VARIABLE  -> EnrichmentSnippet.SnippetType.VARIABLE_USAGES;
                    default        -> EnrichmentSnippet.SnippetType.VARIABLE_USAGES;
                };

                // Filter out DECLARES edges (structural parent→child) —
                // only real usage edges (INVOKES, READS_FIELD, etc.) are interesting
                List<String> usageLines = usageEdges.stream()
                        .filter(e -> e.kind() != EdgeKind.DECLARES)
                        .limit(10)
                        .map(e -> e.filePath() + ":" + e.line())
                        .distinct()
                        .toList();

                if (usageLines.isEmpty()) continue;

                snippets.add(new EnrichmentSnippet(
                        type,
                        decl.filePath(), decl.startLine(), decl.endLine(), decl.simpleName(),
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
            ProjectGraph graph,
            Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;

        ChangedLine first = all.stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .filter(l -> (l.lineNumber() > 0 || l.oldLineNumber() > 0))
                .findFirst().orElse(null);
        if (first == null) return;

        // Normalize path for graph lookup
        String file = graphService.normalizeFilePath(first.filePath(), graph);
        int    line = first.lineNumber() > 0 ? first.lineNumber() : first.oldLineNumber();

        graph.nodesAtLine(file, line).stream()
                .filter(n -> n.kind() == NodeKind.METHOD)
                .min(Comparator.comparingInt(n -> n.endLine() - n.startLine()))
                .ifPresent(method -> {
                    if (full(snippets)) return;
                    int end = Math.min(method.endLine(), method.startLine() + maxSnippetLines - 1);
                    List<String> lines = readLines(repoDir, method.filePath(), method.startLine(), end);
                    if (lines.isEmpty()) return;
                    snippets.add(new EnrichmentSnippet(
                            EnrichmentSnippet.SnippetType.METHOD_BODY,
                            method.filePath(), method.startLine(), end, method.simpleName(),
                            lines,
                            "Body of enclosing method '" + method.simpleName() + "'"
                    ));
                });
    }

    // -----------------------------------------------------------------------
    // Snippet emitters
    // -----------------------------------------------------------------------

    private void emitMethodDeclaration(
            GraphNode node, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> sigLines = readUntilOpenBrace(repoDir, node.filePath(),
                node.startLine(), node.endLine());
        if (sigLines.isEmpty()) return;
        int sigEnd = node.startLine() + sigLines.size() - 1;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                node.filePath(), node.startLine(), sigEnd, node.simpleName(),
                sigLines,
                "Declaration of method '" + node.simpleName() + "' called on changed line"
        ));
    }

    private void emitFieldDeclaration(
            GraphNode node, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> lines = readLines(repoDir, node.filePath(),
                node.startLine(), node.endLine());
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.FIELD_DECLARATION,
                node.filePath(), node.startLine(), node.endLine(), node.simpleName(),
                lines,
                "Declaration of field '" + node.simpleName() + "' accessed on changed line"
        ));
    }

    private void emitVariableDeclaration(
            GraphNode node, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> lines = readLines(repoDir, node.filePath(),
                node.startLine(), node.endLine());
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION,
                node.filePath(), node.startLine(), node.endLine(), node.simpleName(),
                lines,
                "Declaration of variable '" + node.simpleName() + "' used on changed line"
        ));
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
        try { all = Files.readAllLines(file); }
        catch (IOException e) { log.warn("Cannot read {}: {}", file, e.getMessage()); return List.of(); }

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
        while (snippets.size() > maxSnippetsPerGroup) snippets.remove(snippets.size() - 1);
    }

    private String describeDeletedUsages(GraphNode decl, int count) {
        String kind = switch (decl.kind()) {
            case METHOD   -> "method";
            case FIELD    -> "field";
            case VARIABLE -> "variable";
            default       -> "symbol";
        };
        return decl.simpleName() + " (" + kind + ") is deleted but referenced in " + count + " place(s)";
    }
}
