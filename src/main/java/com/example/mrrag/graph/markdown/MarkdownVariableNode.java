package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.GraphBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a VARIABLE (local var / parameter) element.
 * Renamed from {@code VariableNodeView}.
 */
public class MarkdownVariableNode extends MarkdownNode {

    private final List<MarkdownNode> readBy    = new ArrayList<>();
    private final List<MarkdownNode> writtenBy = new ArrayList<>();

    public MarkdownVariableNode(GraphNode node) { super(node); }

    public List<MarkdownNode> getReadBy()    { return readBy; }
    public List<MarkdownNode> getWrittenBy() { return writtenBy; }

    public void addReadBy(MarkdownNode v)    { readBy.add(v); }
    public void addWrittenBy(MarkdownNode v) { writtenBy.add(v); }
}
