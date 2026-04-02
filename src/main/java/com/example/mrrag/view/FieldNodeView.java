package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code FIELD} node.
 */
public class FieldNodeView extends GraphNodeView {

    /** Class that declares this field. */
    private ClassNodeView declaredByClass;
    /** Callables that read this field ({@code READS_FIELD} reverse). */
    private final List<GraphNodeView> readBy      = new ArrayList<>();
    /** Callables that write this field ({@code WRITES_FIELD} reverse). */
    private final List<GraphNodeView> writtenBy   = new ArrayList<>();
    /** Annotation types applied to this field. */
    private final List<GraphNodeView> annotations = new ArrayList<>();

    public FieldNodeView(GraphNode node) {
        super(node);
    }

    public ClassNodeView       getDeclaredByClass() { return declaredByClass; }
    public List<GraphNodeView> getReadBy()          { return readBy; }
    public List<GraphNodeView> getWrittenBy()       { return writtenBy; }
    public List<GraphNodeView> getAnnotations()     { return annotations; }

    void setDeclaredByClass(ClassNodeView v) { this.declaredByClass = v; }
    void addReadBy(GraphNodeView v)          { readBy.add(v); }
    void addWrittenBy(GraphNodeView v)       { writtenBy.add(v); }
    void addAnnotation(GraphNodeView v)      { annotations.add(v); }
}
