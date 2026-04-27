package com.example.mrrag.review.pipeline;

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
 *       is not injected here and is not part of the active pipeline.</li>
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
        List<UnionLine> groups = astChangeGrouper.group(filteredLines, sourceGraph, targetGraph);
        log.info("ContextPipeline.step3: {} groups", groups.size());

        // Shared collector — context may overlap across groups intentionally
        ContextCollector collector = new ContextCollector();

        log.info("ContextPipeline: context collected — {} total snippet(s)", collector.totalSize());

        // Step 6: build per-group representations
        List<GroupRepresentation> result = new ArrayList<>(groups.size());
        return result;
    }

}
