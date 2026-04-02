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

    // -------------------------------------------------------------------------
    // Annotations
    // -------------------------------------------------------------------------

    /**
     * Annotation types applied to this field
     * ({@code ANNOTATED_WITH} outgoing edges).
     *
     * <p>Example: {@code @Autowired}, {@code @Column(name="count")}.
     */
    private final List<GraphNodeView> annotations = new ArrayList<>();

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

    /**
     * Returns the annotation types applied to this field
     * (ANNOTATED_WITH outgoing).
     *
     * @return list of annotation type views; never {@code null}
     */
    public List<GraphNodeView> getAnnotations() { return annotations; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    void setDeclaredByClass(ClassNodeView v) { this.declaredByClass = v; }
    void addReadBy(GraphNodeView v)          { readBy.add(v); }
    void addWrittenBy(GraphNodeView v)       { writtenBy.add(v); }
    void addAnnotation(GraphNodeView v)      { annotations.add(v); }
}
