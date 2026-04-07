package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown node for a FIELD element.
 * Renamed from {@code FieldNodeView}.
 */
public class MarkdownFieldNode extends MarkdownNode {

    private MarkdownClassNode declaredByClass;
    private final List<MarkdownNode> readBy    = new ArrayList<>();
    private final List<MarkdownNode> writtenBy = new ArrayList<>();

    public MarkdownFieldNode(GraphNode node) { super(node); }

    public MarkdownClassNode getDeclaredByClass() { return declaredByClass; }
    public List<MarkdownNode>    getReadBy()          { return readBy; }
    public List<MarkdownNode>    getWrittenBy()       { return writtenBy; }

    public void setDeclaredByClass(MarkdownClassNode v) { this.declaredByClass = v; }
    public void addReadBy(MarkdownNode v)               { readBy.add(v); }
    public void addWrittenBy(MarkdownNode v)            { writtenBy.add(v); }
}
