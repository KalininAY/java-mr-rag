package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.UnionLine;
import com.example.mrrag.review.union.UnionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AstChangeGrouper {

    private final GraphQueryService graphQuery;
    private final UnionService unionService;

    public List<UnionLine> group(Set<ChangedLine> changedLines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        Objects.requireNonNull(sourceGraph, "sourceGraph must not be null for AstChangeGrouper");

        // Step 1: resolve AST anchor nodes for every non-CONTEXT changed line
        Map<ChangedLine, Set<GraphNode>> lineToNodes = resolveNodes(changedLines, sourceGraph, targetGraph);
        log.debug("Step 1 (resolve nodes): {}/{} lines have at least one AST node",
                lineToNodes.values().stream().filter(s -> !s.isEmpty()).count(),
                changedLines.size());

        // Step 2: union ChangedLines
        List<UnionLine> unionLines = unionService.buildUnionLines(lineToNodes);
        log.debug("Step 2 (UnionLines): {}", unionLines.size());

        return unionLines;
    }

    /**
     * For each non-CONTEXT changed line, delegates to
     * {@link GraphQueryService#getNodesWithLine} to find all AST nodes
     * declared in or referenced from the line's effective line number.
     *
     * <p>Lines with no resolvable AST node are stored with an empty set;
     * they will fall through to the per-file fallback group in Step 3.
     */
    private Map<ChangedLine, Set<GraphNode>> resolveNodes(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        Map<ChangedLine, Set<GraphNode>> result = new LinkedHashMap<>();
        for (ChangedLine line : lines) {
            String filepath = line.filePath();
            List<GraphNode> nodes;
            if (line.type() == ChangedLine.LineType.CONTEXT) continue;
            if (line.type() == ChangedLine.LineType.ADD)
                nodes = graphQuery.getNodesWithLine(filepath, line.lineNumber(), sourceGraph);
            else
                nodes = graphQuery.getNodesWithLine(filepath, line.oldLineNumber(), targetGraph);
            result.put(line, new LinkedHashSet<>(nodes));
        }
        return result;
    }
}
