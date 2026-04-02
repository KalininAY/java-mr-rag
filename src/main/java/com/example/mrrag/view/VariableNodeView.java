package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code VARIABLE} node (local variable or method parameter).
 */
public class VariableNodeView extends GraphNodeView {

    /** Callables that read this variable ({@code READS_LOCAL_VAR} reverse). */
    private final List<GraphNodeView> readBy    = new ArrayList<>();
    /** Callables that write this variable ({@code WRITES_LOCAL_VAR} reverse). */
    private final List<GraphNodeView> writtenBy = new ArrayList<>();

    public VariableNodeView(GraphNode node) {
        super(node);
    }

    public List<GraphNodeView> getReadBy()    { return readBy; }
    public List<GraphNodeView> getWrittenBy() { return writtenBy; }

    void addReadBy(GraphNodeView v)    { readBy.add(v); }
    void addWrittenBy(GraphNodeView v) { writtenBy.add(v); }
}
