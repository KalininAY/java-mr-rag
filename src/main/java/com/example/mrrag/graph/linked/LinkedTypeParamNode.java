package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a TYPE_PARAM element.
 * Renamed from {@code TypeParamNodeView}.
 */
public class LinkedTypeParamNode extends LinkedNode {

    private LinkedNode             owner;
    private final List<LinkedNode> bounds = new ArrayList<>();

    public LinkedTypeParamNode(GraphNode node) { super(node); }

    public LinkedNode           getOwner()  { return owner; }
    public List<LinkedNode>     getBounds() { return bounds; }

    public void setOwner(LinkedNode v)  { this.owner = v; }
    public void addBound(LinkedNode v)  { bounds.add(v); }
}
