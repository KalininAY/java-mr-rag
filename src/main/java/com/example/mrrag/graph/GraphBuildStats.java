package com.example.mrrag.graph;

import com.example.mrrag.graph.GraphBuilder.EdgeKind;
import com.example.mrrag.graph.GraphBuilder.NodeKind;

import java.util.Map;

/**
 * Statistics returned by
 * {@code POST /api/graph/ingest} after a repository has been cloned
 * and its symbol graph has been built.
 *
 * @param repoUrl          the URL that was cloned
 * @param cloneDir         temporary directory where the repo was cloned
 * @param cloneMs          time spent cloning the repository (ms)
 * @param buildMs          time spent building the Spoon AST graph (ms)
 * @param totalMs          total wall-clock time (ms)
 * @param totalNodes       total number of graph nodes
 * @param totalEdges       total number of graph edges
 * @param nodesByKind      node counts grouped by {@link NodeKind}
 * @param edgesByKind      edge counts grouped by {@link EdgeKind}
 * @param uniqueFiles      number of unique source files indexed
 */
public record GraphBuildStats(
        String              repoUrl,
        String              cloneDir,
        long                cloneMs,
        long                buildMs,
        long                totalMs,
        int                 totalNodes,
        long                totalEdges,
        Map<NodeKind, Long> nodesByKind,
        Map<EdgeKind, Long> edgesByKind,
        int                 uniqueFiles
) {}
