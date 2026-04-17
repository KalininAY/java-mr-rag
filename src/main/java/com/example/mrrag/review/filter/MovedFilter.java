package com.example.mrrag.review.filter;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MovedFilter implements ContextFilter {

    private final GraphQueryService graphQuery;

    @Override
    public Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        Set<String> movedMethodIds = graphQuery.findMovedMethodIds(sourceGraph, targetGraph);
        Set<String> movedFieldIds = graphQuery.findMovedFieldIds(sourceGraph, targetGraph);

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

        Set<ChangedLine> result = new LinkedHashSet<>();
        int removed = 0;
        for (ChangedLine line : lines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) {
                result.add(line);
                continue;
            }
            if (!line.filePath().endsWith(".java")) {
                result.add(line);
                continue;
            }

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
