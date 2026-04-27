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
 * Stages:
 * <ol>
 *   <li><b>Parse</b> — {@link DiffParser}: GitLab {@link Diff}s → {@link ChangedLine}s.</li>
 *   <li><b>Filter</b> — ordered {@link ContextFilter} chain.</li>
 *   <li><b>Group</b> — {@link AstChangeGrouper}: lines → {@link UnionLine}s via AST graph.</li>
 *   <li><b>Collect context</b> — all {@link ContextStrategy}s applied to each union;
 *       each strategy filters applicable lines internally.</li>
 *   <li><b>Represent</b> — {@link GroupRepresentationBuilder}: one {@link GroupRepresentation} per union.</li>
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
     * Execute the full context pipeline.
     *
     * @param diffs       source diffs from MR
     * @param sourceGraph AST graph of the source (feature) branch
     * @param targetGraph AST graph of the target (base) branch
     * @return one {@link GroupRepresentation} per union, in original order
     */
    public List<GroupRepresentation> run(List<Diff> diffs, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        log.info("ContextPipeline.run: {} diffs", diffs.size());

        // Step 1: parse diffs to ChangedLine
        Set<ChangedLine> changedLines = diffParser.parse(diffs);
        log.info("ContextPipeline.step1: {} changedLines", changedLines.size());

        // Step 2: ordered filter chain
        Set<ChangedLine> filteredLines = filters.stream()
                .reduce(
                        changedLines,
                        (acc, filter) -> filter.filter(acc, sourceGraph, targetGraph),
                        (left, right) -> {
                            throw new UnsupportedOperationException(
                                    "ContextFilter chain is sequential; parallel combine is undefined");
                        });
        log.info("ContextPipeline.step2: {} filteredLines", filteredLines.size());

        // Step 3: group lines into UnionLines via AST graph
        List<UnionLine> unions = astChangeGrouper.group(filteredLines, sourceGraph, targetGraph);
        log.info("ContextPipeline.step3: {} unions", unions.size());

        // Steps 4-5: collect context + build representations
        List<GroupRepresentation> result = new ArrayList<>(unions.size());
        for (UnionLine union : unions) {
            List<EnrichmentSnippet> snippets = strategies.stream()
                    .flatMap(s -> s.collectContext(union, sourceGraph, targetGraph).stream())
                    .toList();
            log.debug("ContextPipeline: union={} snippets={}", union.id(), snippets.size());
            result.add(representationBuilder.build(union, snippets, sourceGraph));
        }

        log.info("ContextPipeline: done — {} representation(s), {} total snippet(s)",
                result.size(),
                result.stream().mapToInt(r -> r.contextSnippets().size()).sum());
        return result;
    }
}
