package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.model.GraphNode;

import java.util.Set;

/**
 * Represents a directed connection between two "anchor" AST nodes
 * discovered among changed lines, together with any intermediate nodes
 * found on the BFS path between them.
 *
 * <p>{@code node1} and {@code node2} are the anchors — nodes whose
 * containing changed lines drove the grouping.
 * {@code intermediateNodes} are nodes on the shortest path
 * {@code node1 → … → node2} in the AST graph; they are <em>not</em>
 * changed themselves, but provide semantic context explaining why the
 * two anchors belong together.
 *
 * <p>Direction matters: a {@code NodeConnection(A, B, {C})} and
 * {@code NodeConnection(B, A, {D})} are distinct and may produce
 * separate groups.
 *
 * @param node1             source anchor node
 * @param node2             target anchor node
 * @param intermediateNodes nodes on the path between node1 and node2
 *                          (empty when node1 and node2 are directly connected)
 */
public record NodeConnection(
        GraphNode node1,
        GraphNode node2,
        Set<GraphNode> intermediateNodes
) {}
