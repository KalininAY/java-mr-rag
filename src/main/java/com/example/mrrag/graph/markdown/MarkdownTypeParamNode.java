package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.GraphBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a TYPE_PARAM element.
 * Renamed from {@code TypeParamNodeView}.
 */
public class MarkdownTypeParamNode extends MarkdownNode {

    private MarkdownNode owner;
    private final List<MarkdownNode> bounds = new ArrayList<>();

    public MarkdownTypeParamNode(GraphNode node) { super(node); }

    public MarkdownNode getOwner()  { return owner; }
    public List<MarkdownNode>     getBounds() { return bounds; }

    public void setOwner(MarkdownNode v)  { this.owner = v; }
    public void addBound(MarkdownNode v)  { bounds.add(v); }
}
