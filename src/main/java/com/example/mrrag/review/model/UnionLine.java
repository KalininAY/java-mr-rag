package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

import java.util.Set;

public record UnionLine(String id,
                        Set<ChangedLine> changedLines,
                        Set<GraphNode> graphNodes) {}
