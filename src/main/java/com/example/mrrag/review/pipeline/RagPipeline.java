package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextCollector;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * Core RAG pipeline algorithm.
 *
 * <ol>
 *   <li><b>Diffs</b> — taken as already-parsed {@link ChangeGroup} list.</li>
 *   <li><b>Split into groups</b> — classify each group by {@link ChangeType}.</li>
 *   <li><b>Per group: find relevant classes</b> — {@link GraphNode}s of kind
 *       CLASS / INTERFACE / ENUM from source <em>and</em> target graphs whose
 *       file path matches the group's changed files.</li>
 *   <li><b>Per group: find relevant nodes</b> — members of those classes plus
 *       nodes directly at the changed lines.</li>
 *   <li><b>Per change type: apply strategy → collect context</b> — the
 *       appropriate {@link ContextStrategy} is selected; its results are stored
 *       in a shared {@link ContextCollector}.  Context may duplicate across
 *       groups by design.</li>
 *   <li><b>Build representations</b> — one {@link GroupRepresentation} per
 *       group via {@link GroupRepresentationBuilder}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPipeline {

    private final List<ContextStrategy> strategies;
    private final GroupRepresentationBuilder representationBuilder;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Execute the full RAG pipeline.
     *
     * @param groups        change groups produced by ChangeGrouper
     * @param sourceGraph   AST graph of the source (feature) branch
     * @param targetGraph   AST graph of the target (base) branch
     * @param sourceRepoDir local path to the cloned source branch
     * @param targetRepoDir local path to the cloned target branch
     * @return one {@link GroupRepresentation} per input group, in original order
     */
    public List<GroupRepresentation> run(
            List<ChangeGroup> groups,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        log.info("RagPipeline.run: {} groups", groups.size());

        // Step 1: diffs already passed in as ChangeGroups

        // Step 2: classify groups by ChangeType
        Map<ChangeType, List<ChangeGroup>> byType = classifyGroups(groups);
        byType.forEach((t, gs) -> log.info("  ChangeType={}: {} group(s)", t, gs.size()));

        // Shared collector — context may overlap across groups intentionally
        ContextCollector collector = new ContextCollector();

        // Steps 3–5: per group find classes/nodes, per type collect context
        for (Map.Entry<ChangeType, List<ChangeGroup>> entry : byType.entrySet()) {
            ChangeType type      = entry.getKey();
            List<ChangeGroup> gs = entry.getValue();

            List<ContextStrategy> applicable = strategies.stream()
                    .filter(s -> s.supports(type))
                    .toList();

            if (applicable.isEmpty()) {
                log.warn("RagPipeline: no ContextStrategy for ChangeType={}", type);
            }

            for (ChangeGroup group : gs) {

                // Step 3: relevant classes in source + target graphs
                List<GraphNode> relevantClasses =
                        findRelevantClasses(group, sourceGraph, targetGraph);
                log.debug("Group {} ({}): {} relevant class(es)",
                        group.id(), type, relevantClasses.size());

                // Step 4: relevant nodes — members of those classes + nodes at changed lines
                List<GraphNode> relevantNodes =
                        findRelevantNodes(group, relevantClasses, sourceGraph, targetGraph);
                log.debug("Group {} ({}): {} relevant node(s)",
                        group.id(), type, relevantNodes.size());

                // Step 5: apply strategies → add to collector
                for (ContextStrategy strategy : applicable) {
                    List<EnrichmentSnippet> ctx = strategy.collectContext(
                            group, sourceGraph, targetGraph, sourceRepoDir, targetRepoDir);
                    collector.addAll(group.id(), ctx);
                }
            }
        }

        log.info("RagPipeline: context collected — {} total snippet(s)", collector.totalSize());

        // Step 6: build per-group representations
        List<GroupRepresentation> result = new ArrayList<>(groups.size());
        for (ChangeGroup group : groups) {
            ChangeType type               = classifyGroup(group);
            List<EnrichmentSnippet> ctx   = collector.get(group.id());
            GroupRepresentation repr      = representationBuilder.build(group, type, ctx);
            result.add(repr);
            log.debug("Representation: group={} type={} snippets={}",
                    group.id(), type, ctx.size());
        }

        log.info("RagPipeline: {} representation(s) built", result.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Step 2: classify
    // -----------------------------------------------------------------------

    private Map<ChangeType, List<ChangeGroup>> classifyGroups(List<ChangeGroup> groups) {
        Map<ChangeType, List<ChangeGroup>> result = new LinkedHashMap<>();
        for (ChangeGroup g : groups) {
            result.computeIfAbsent(classifyGroup(g), k -> new ArrayList<>()).add(g);
        }
        return result;
    }

    /**
     * Classify a single group by inspecting its {@link ChangedLine} types and
     * the number of distinct files involved.
     */
    public static ChangeType classifyGroup(ChangeGroup group) {
        boolean hasAdd    = false;
        boolean hasDelete = false;
        Set<String> files = new HashSet<>();

        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.ADD)    hasAdd    = true;
            if (l.type() == ChangedLine.LineType.DELETE) hasDelete = true;
            files.add(l.filePath());
        }

        if (files.size() > 1)       return ChangeType.CROSS_SCOPE;
        if (hasAdd && hasDelete)    return ChangeType.MODIFICATION;
        if (hasDelete)              return ChangeType.DELETION;
        return ChangeType.ADDITION;
    }

    // -----------------------------------------------------------------------
    // Step 3: find relevant classes (CLASS / INTERFACE / ENUM nodes)
    // -----------------------------------------------------------------------

    private List<GraphNode> findRelevantClasses(
            ChangeGroup group,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph
    ) {
        Set<String> changedFiles = new HashSet<>();
        for (ChangedLine l : group.changedLines()) changedFiles.add(l.filePath());

        List<GraphNode> result = new ArrayList<>();
        for (String rawFile : changedFiles) {
            collectClassNodes(rawFile, sourceGraph, result);
            collectClassNodes(rawFile, targetGraph, result);
        }
        return result;
    }

    private void collectClassNodes(String rawFile, ProjectGraph graph, List<GraphNode> out) {
        if (graph == null) return;
        String file = AstGraphUtils.normalizeFilePath(rawFile, graph);
        graph.nodes.values().stream()
                .filter(n -> file.equals(n.filePath()))
                .filter(n -> n.kind() == NodeKind.CLASS
                          || n.kind() == NodeKind.INTERFACE
                          || n.kind() == NodeKind.ENUM)
                .forEach(out::add);
    }

    // -----------------------------------------------------------------------
    // Step 4: find relevant nodes by classes (members + nodes at changed lines)
    // -----------------------------------------------------------------------

    private List<GraphNode> findRelevantNodes(
            ChangeGroup group,
            List<GraphNode> relevantClasses,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph
    ) {
        List<GraphNode> result = new ArrayList<>();

        // Members declared inside the relevant classes (via DECLARES edges)
        for (ProjectGraph graph : List.of(sourceGraph, targetGraph)) {
            if (graph == null) continue;
            for (GraphNode cls : relevantClasses) {
                graph.outgoing(cls.id()).stream()
                        .map(e -> graph.nodes.get(e.callee()))
                        .filter(Objects::nonNull)
                        .forEach(result::add);
            }
        }

        // Nodes directly at changed lines
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            int line = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
            if (line <= 0) continue;
            for (ProjectGraph graph : List.of(sourceGraph, targetGraph)) {
                if (graph == null) continue;
                String file = AstGraphUtils.normalizeFilePath(l.filePath(), graph);
                result.addAll(graph.nodesAtLine(file, line));
            }
        }
        return result;
    }
}
