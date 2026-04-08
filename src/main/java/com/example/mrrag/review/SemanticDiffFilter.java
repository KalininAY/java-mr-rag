package com.example.mrrag.review;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Variant B semantic diff filter: removes lines that represent a pure
 * <em>move</em> of a Java symbol (method or field relocated without any
 * body/signature change) from the raw git diff.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Collect method/field node IDs that appear in <em>both</em> source and
 *       target {@link ProjectGraph}s with identical body hash / type signature
 *       but at a different position — via {@link GraphQueryService}.</li>
 *   <li>Expand those IDs to full line ranges in each graph.</li>
 *   <li>Remove {@code ADD} lines falling in the source ranges and
 *       {@code DELETE} lines falling in the target ranges.</li>
 *   <li>{@code CONTEXT} lines and non-Java files are never removed.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticDiffFilter {

    private final GraphQueryService graphQuery;

    /**
     * Filter {@code lines} by removing ADD/DELETE entries that correspond to
     * purely moved (unchanged) symbols.
     *
     * @param lines       raw changed lines from the diff parser
     * @param sourceGraph AST graph of the source (feature) branch
     * @param targetGraph AST graph of the target (base) branch
     * @return filtered list — MOVED lines removed, everything else preserved
     */
    public List<ChangedLine> filter(
            List<ChangedLine> lines,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph
    ) {
        Set<String> movedMethodIds = graphQuery.findMovedMethodIds(sourceGraph, targetGraph);
        Set<String> movedFieldIds  = graphQuery.findMovedFieldIds(sourceGraph, targetGraph);

        if (movedMethodIds.isEmpty() && movedFieldIds.isEmpty()) {
            return lines;
        }

        log.debug("Moved methods: {}, moved fields: {}", movedMethodIds.size(), movedFieldIds.size());

        Set<GraphQueryService.LineKey> addLinesToRemove = new HashSet<>();
        addLinesToRemove.addAll(graphQuery.methodLineRanges(sourceGraph, movedMethodIds));
        addLinesToRemove.addAll(graphQuery.fieldDeclLines(sourceGraph, movedFieldIds));

        Set<GraphQueryService.LineKey> deleteLinesToRemove = new HashSet<>();
        deleteLinesToRemove.addAll(graphQuery.methodLineRanges(targetGraph, movedMethodIds));
        deleteLinesToRemove.addAll(graphQuery.fieldDeclLines(targetGraph, movedFieldIds));

        List<ChangedLine> result = new ArrayList<>();
        int removed = 0;
        for (ChangedLine line : lines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) { result.add(line); continue; }
            if (!line.filePath().endsWith(".java"))          { result.add(line); continue; }

            if (line.type() == ChangedLine.LineType.ADD
                    && addLinesToRemove.contains(
                            new GraphQueryService.LineKey(line.filePath(), line.lineNumber()))) {
                log.trace("Filtering MOVED ADD  {}:{}", line.filePath(), line.lineNumber());
                removed++;
                continue;
            }
            if (line.type() == ChangedLine.LineType.DELETE
                    && deleteLinesToRemove.contains(
                            new GraphQueryService.LineKey(line.filePath(), line.oldLineNumber()))) {
                log.trace("Filtering MOVED DEL  {}:{}", line.filePath(), line.oldLineNumber());
                removed++;
                continue;
            }
            result.add(line);
        }

        log.info("SemanticDiffFilter: removed {} MOVED lines ({} methods, {} fields moved)",
                removed, movedMethodIds.size(), movedFieldIds.size());
        return result;
    }
}
