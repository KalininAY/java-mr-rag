package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Applies incremental updates to a live {@link ProjectGraph}.
 *
 * <h2>Patch lifecycle</h2>
 * <ol>
 *   <li>{@link #removeFiles} — evicts all nodes and edges belonging to the
 *       changed files from every index in {@link ProjectGraph}.</li>
 *   <li>{@link #addFiles} — re-parses changed files via Spoon (reusing the
 *       existing {@link GraphBuilder#buildBatch} logic) and merges the
 *       resulting partial graph into the live graph.</li>
 * </ol>
 *
 * <p>Both methods operate in-place on the supplied {@code ProjectGraph} and
 * are <em>not</em> atomic — the caller ({@link IncrementalGraphBuilder})
 * is responsible for deciding when the graph is safe to expose.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphPatcher {

    /** Exposed for patching — package-private bridge into GraphBuilderImpl. */
    private final GraphBuilder graphBuilderImpl;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Removes all nodes and edges that originate from the given file paths.
     *
     * <p>After this call the graph contains no trace of the supplied files:
     * <ul>
     *   <li>nodes whose {@code filePath} matches are removed from
     *       {@code nodes}, {@code bySimpleName}, {@code byLine}, {@code byFile};</li>
     *   <li>edges sourced from those nodes are removed from
     *       {@code edgesFrom} and {@code edgesTo};</li>
     *   <li>edges in other nodes' {@code edgesFrom}/{@code edgesTo} lists
     *       whose {@code file()} matches are also pruned.</li>
     * </ul>
     *
     * @param graph     the live graph to mutate
     * @param filePaths repository-relative paths of files to remove
     */
    public void removeFiles(ProjectGraph graph, Collection<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;

        int removedNodes = 0;
        int removedEdges = 0;

        for (String filePath : filePaths) {
            // 1. Collect nodes belonging to this filePath
            List<GraphNode> fileNodes = graph.byFile.remove(filePath);
            if (fileNodes == null) {
                log.debug("GraphPatcher.removeFiles: no nodes for filePath {}", filePath);
                continue;
            }

            for (GraphNode node : fileNodes) {
                // Remove from primary index
                graph.nodes.remove(node.id());

                // Remove from bySimpleName
                graph.bySimpleName.computeIfPresent(node.simpleName(), (k, list) -> {
                    list.remove(node);
                    return list.isEmpty() ? null : list;
                });

                // Remove from byLine
                String lineKey = node.filePath() + "#" + node.startLine();
                graph.byLine.computeIfPresent(lineKey, (k, list) -> {
                    list.remove(node);
                    return list.isEmpty() ? null : list;
                });

                // Remove all outgoing edges from this node
                List<GraphEdge> outgoing = graph.edgesFrom.remove(node.id());
                if (outgoing != null) {
                    removedEdges += outgoing.size();
                    // Clean reverse index
                    for (GraphEdge e : outgoing) {
                        graph.edgesTo.computeIfPresent(e.callee(), (k, list) -> {
                            list.remove(e);
                            return list.isEmpty() ? null : list;
                        });
                    }
                }

                // Remove all incoming edges to this node
                List<GraphEdge> incoming = graph.edgesTo.remove(node.id());
                if (incoming != null) {
                    removedEdges += incoming.size();
                    for (GraphEdge e : incoming) {
                        graph.edgesFrom.computeIfPresent(e.caller(), (k, list) -> {
                            list.remove(e);
                            return list.isEmpty() ? null : list;
                        });
                    }
                }

                removedNodes++;
            }

            // 2. Prune stale edges in other nodes that reference this filePath
            //    (e.g. an INVOKES edge recorded at the call-site filePath)
            pruneEdgesByFile(graph.edgesFrom, filePath);
            pruneEdgesByFile(graph.edgesTo,   filePath);
        }

        log.info("GraphPatcher.removeFiles: removed {} nodes, ~{} edges for {} files",
                removedNodes, removedEdges, filePaths.size());
    }

    /**
     * Parses {@code newSources} via Spoon and merges the resulting partial
     * graph into {@code graph}.
     *
     * @param graph       the live graph to merge into
     * @param newSources  new/changed source files to re-parse
     * @param projectRoot classpath root for Spoon (may be {@code null})
     */
    public void addFiles(ProjectGraph graph, List<ProjectSource> newSources, Path projectRoot) {
        if (newSources == null || newSources.isEmpty()) return;

        log.info("GraphPatcher.addFiles: re-parsing {} files", newSources.size());
        ProjectGraph patch = graphBuilderImpl.buildBatch(newSources, projectRoot);

        int nodesBefore = graph.nodes.size();
        graphBuilderImpl.mergeGraphs(graph, patch);
        log.info("GraphPatcher.addFiles: merged patch — added {} nodes",
                graph.nodes.size() - nodesBefore);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static void pruneEdgesByFile(Map<String, List<GraphEdge>> edgeMap, String file) {
        edgeMap.forEach((id, edges) -> edges.removeIf(e -> file.equals(e.filePath())));
        // Remove empty lists to keep the map tidy
        edgeMap.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
