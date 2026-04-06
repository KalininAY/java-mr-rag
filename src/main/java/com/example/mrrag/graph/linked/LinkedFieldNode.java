package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a FIELD element.
 * Renamed from {@code FieldNodeView}.
 */
public class LinkedFieldNode extends LinkedNode {

    private LinkedClassNode        declaredByClass;
    private final List<LinkedNode> readBy    = new ArrayList<>();
    private final List<LinkedNode> writtenBy = new ArrayList<>();

    public LinkedFieldNode(GraphNode node) { super(node); }

    public LinkedClassNode     getDeclaredByClass() { return declaredByClass; }
    public List<LinkedNode>    getReadBy()          { return readBy; }
    public List<LinkedNode>    getWrittenBy()       { return writtenBy; }

    public void setDeclaredByClass(LinkedClassNode v) { this.declaredByClass = v; }
    public void addReadBy(LinkedNode v)               { readBy.add(v); }
    public void addWrittenBy(LinkedNode v)            { writtenBy.add(v); }
}
