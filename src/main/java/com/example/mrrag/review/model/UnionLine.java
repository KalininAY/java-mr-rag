package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A union of changed lines sharing at least one common GraphNode.
 *
 * @param id           unique identifier of this union (derived from member line positions)
 * @param changedLines all changed lines in this union
 * @param graphNodes   all GraphNodes referenced by the changed lines
 * @param nodeOrigins  mapping from each GraphNode to all ChangedLines that resolved it;
 *                     a node may be resolved from multiple lines (e.g. both ADD and DELETE),
 *                     used by node-level context strategies to determine which graph
 *                     (source or target) to search and what change types apply
 */
public record UnionLine(String id,
                        Set<ChangedLine> changedLines,
                        Set<GraphNode> graphNodes,
                        Map<GraphNode, List<ChangedLine>> nodeOrigins) {}
