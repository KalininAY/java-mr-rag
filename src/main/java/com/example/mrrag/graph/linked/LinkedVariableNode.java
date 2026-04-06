package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a VARIABLE (local var / parameter) element.
 * Renamed from {@code VariableNodeView}.
 */
public class LinkedVariableNode extends LinkedNode {

    private final List<LinkedNode> readBy    = new ArrayList<>();
    private final List<LinkedNode> writtenBy = new ArrayList<>();

    public LinkedVariableNode(GraphNode node) { super(node); }

    public List<LinkedNode> getReadBy()    { return readBy; }
    public List<LinkedNode> getWrittenBy() { return writtenBy; }

    public void addReadBy(LinkedNode v)    { readBy.add(v); }
    public void addWrittenBy(LinkedNode v) { writtenBy.add(v); }
}
