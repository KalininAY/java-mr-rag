package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#FIELD} node.
 *
 * <p>Represents an instance or static field declared in a class.
 * All list fields are pre-populated by
 * {@link com.example.mrrag.service.GraphViewBuilder}.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed field declaration
 * line, e.g. {@code "private int count = 0;"}.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}; it is populated from {@code ANNOTATED_WITH} outgoing
 * edges by {@link com.example.mrrag.service.GraphViewBuilder}.
 */
public class FieldNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The class that declares this field.
     * Populated from the reverse of the {@code DECLARES} edge
     * ({@code CLASS → FIELD}).
     */
    private ClassNodeView declaredByClass;

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    /**
     * Callables (methods, constructors, lambdas) that read this field
     * (reverse {@code READS_FIELD} edges).
     */
    private final List<GraphNodeView> readBy = new ArrayList<>();

    /**
     * Callables (methods, constructors, lambdas) that write this field
     * (reverse {@code WRITES_FIELD} edges).
     */
    private final List<GraphNodeView> writtenBy = new ArrayList<>();

    public FieldNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the class that declares this field.
     *
     * @return declaring class view; never {@code null} after wiring
     */
    public ClassNodeView getDeclaredByClass()   { return declaredByClass; }

    /**
     * Returns the callables that read this field (reverse READS_FIELD).
     *
     * @return list of reader views; never {@code null}
     */
    public List<GraphNodeView> getReadBy()      { return readBy; }

    /**
     * Returns the callables that write this field (reverse WRITES_FIELD).
     *
     * @return list of writer views; never {@code null}
     */
    public List<GraphNodeView> getWrittenBy()   { return writtenBy; }

    // Annotations are inherited from GraphNodeView.getAnnotatedBy().

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByClass(ClassNodeView v) { this.declaredByClass = v; }
    public void addReadBy(GraphNodeView v)          { readBy.add(v); }
    public void addWrittenBy(GraphNodeView v)       { writtenBy.add(v); }
}
