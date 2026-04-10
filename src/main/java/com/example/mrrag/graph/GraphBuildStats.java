package com.example.mrrag.graph;

import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.NodeKind;

import java.util.Map;

/**
 * Statistics returned by
 * {@code POST /api/graph/ingest} after a repository has been cloned
 * and its symbol graph has been built.
 *
 * @param namespace   namespace project
 * @param repo        name project
 * @param branchOrTag reference in project
 * @param cloneDir    temporary directory where the repo was cloned
 * @param buildMs     time spent building the Spoon AST graph (ms)
 * @param totalMs     total wall-clock time (ms)
 * @param totalNodes  total number of graph nodes
 * @param totalEdges  total number of graph edges
 * @param nodesByKind node counts grouped by {@link NodeKind}
 * @param edgesByKind edge counts grouped by {@link EdgeKind}
 * @param uniqueFiles number of unique source files indexed
 */
public record GraphBuildStats(
        String namespace,
        String repo,
        String branchOrTag,
        String cloneDir,
        long buildMs,
        long totalMs,
        int totalNodes,
        long totalEdges,
        Map<NodeKind, Long> nodesByKind,
        Map<EdgeKind, Long> edgesByKind,
        int uniqueFiles
) {
}
