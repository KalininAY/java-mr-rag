package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.filter.ContextFilter;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * RAG context pipeline for MR review.
 * <p>
 * Stages match {@link #run}:
 * <ol>
 *   <li><b>Parse</b> — {@link DiffParser}: GitLab {@link Diff}s → {@link ChangedLine}s.</li>
 *   <li><b>Filter</b> — ordered {@link ContextFilter} chain (each sees the previous output).</li>
 *   <li><b>Group</b> — {@link AstChangeGrouper}: lines → {@link ChangeGroup}s via pure AST graph.
 *       {@link ChangeGrouper} is retained as an internal fallback (no-graph scenarios) but
 *       is not injected here and is not part of the active pipeline.</li>
 *   <li><b>Classify</b> — {@link #classifyGroup} → {@link ChangeType} per group.</li>
 *   <li><b>Collect context</b> — per type, {@link ContextStrategy}s → {@link ContextCollector}
 *       (also resolves relevant classes/nodes for logging / future use).</li>
 *   <li><b>Represent</b> — {@link GroupRepresentationBuilder}: one {@link GroupRepresentation} per group.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextPipeline {

    private final List<ContextStrategy> strategies;
    private final List<ContextFilter> filters;
    private final DiffParser diffParser;
    private final AstChangeGrouper astChangeGrouper;
    private final GroupRepresentationBuilder representationBuilder;

    /**
     * Execute the full Context pipeline.
     *
     * @param diffs       source diffs from MR
     * @param sourceGraph AST graph of the source (feature) branch
     * @param targetGraph AST graph of the target (base) branch
     * @return one {@link GroupRepresentation} per input group, in original order
     */
    public List<GroupRepresentation> run(List<Diff> diffs, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        log.info("ContextPipeline.run: {} diffs", diffs.size());

        // Step 1: parse diffs to ChangedLine
        Set<ChangedLine> changedLines = diffParser.parse(diffs);
        log.info("ContextPipeline.step1: {} changedLines", changedLines.size());

        // Step 2: ordered filter chain via reduce (sequential stream — combiner unused)
        Set<ChangedLine> filteredLines = filters.stream()
                .reduce(
                        changedLines,
                        (acc, filter) -> filter.filter(acc, sourceGraph, targetGraph),
                        (left, right) -> {
                            throw new UnsupportedOperationException(
                                    "ContextFilter chain is sequential; parallel combine is undefined");
                        });
        log.info("ContextPipeline.step2: {} filteredLines", filteredLines.size());

        // Step 3: group lines via AST graph
        List<ChangeGroup> groups = astChangeGrouper.group(filteredLines, sourceGraph, targetGraph);
        log.info("ContextPipeline.step3: {} groups", groups.size());

        // Step 4: classify groups by ChangeType
        Map<ChangeType, List<ChangeGroup>> byType = classifyGroups(groups);
        log.info("ContextPipeline.step4: {} classifyGroups", byType.size());
        byType.forEach((t, gs) -> log.info("  ChangeType={}: {} group(s)", t, gs.size()));

        // Shared collector — context may overlap across groups intentionally
        ContextCollector collector = new ContextCollector();

        // Steps 5: per group find classes/nodes, per type collect context
        for (Map.Entry<ChangeType, List<ChangeGroup>> entry : byType.entrySet()) {
            ChangeType type = entry.getKey();
            List<ChangeGroup> gs = entry.getValue();

            List<ContextStrategy> applicable = strategies.stream()
                    .filter(s -> s.supports(type))
                    .toList();

            if (applicable.isEmpty()) log.warn("ContextPipeline: no ContextStrategy for ChangeType={}", type);

            for (ChangeGroup group : gs) {
                // Step 5.1: relevant classes in source + target graphs
                List<GraphNode> relevantClasses =
                        findRelevantClasses(group, sourceGraph, targetGraph);
                log.debug("Group {} ({}): {} relevant class(es)",
                        group.id(), type, relevantClasses.size());

                // Step 5.2: relevant nodes — members of those classes + nodes at changed lines
                List<GraphNode> relevantNodes =
                        findRelevantNodes(group, relevantClasses, sourceGraph, targetGraph);
                log.debug("Group {} ({}): {} relevant node(s)",
                        group.id(), type, relevantNodes.size());

                // Step 5.3: apply strategies → add to collector
                for (ContextStrategy strategy : applicable) {
                    List<EnrichmentSnippet> ctx = strategy.collectContext(group, sourceGraph, targetGraph);
                    collector.addAll(group.id(), ctx);
                }
            }
        }

        log.info("ContextPipeline: context collected — {} total snippet(s)", collector.totalSize());

        // Step 6: build per-group representations
        List<GroupRepresentation> result = new ArrayList<>(groups.size());
        for (ChangeGroup group : groups) {
            ChangeType type = classifyGroup(group);
            List<EnrichmentSnippet> ctx = collector.get(group.id());
            GroupRepresentation repr = representationBuilder.build(group, type, ctx, sourceGraph);
            result.add(repr);
            log.debug("Representation: group={} type={} snippets={}",
                    group.id(), type, ctx.size());
        }

        log.info("ContextPipeline: {} representation(s) built", result.size());
        return result;
    }

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
        boolean hasAdd = false;
        boolean hasDelete = false;
        Set<String> files = new HashSet<>();

        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.ADD) hasAdd = true;
            if (l.type() == ChangedLine.LineType.DELETE) hasDelete = true;
            files.add(l.filePath());
        }

        if (files.size() > 1) return ChangeType.CROSS_SCOPE;
        if (hasAdd && hasDelete) return ChangeType.MODIFICATION;
        if (hasDelete) return ChangeType.DELETION;
        return ChangeType.ADDITION;
    }

    private List<GraphNode> findRelevantClasses(ChangeGroup group, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
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
                        || n.kind() == NodeKind.INTERFACE)
                .forEach(out::add);
    }

    private List<GraphNode> findRelevantNodes(ChangeGroup group, List<GraphNode> relevantClasses,
                                               ProjectGraph sourceGraph, ProjectGraph targetGraph) {
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
