package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#TYPE_PARAM} node.
 *
 * <p>Represents a generic type parameter declaration.  Examples:
 * <ul>
 *   <li>{@code T} in {@code class Foo<T>}</li>
 *   <li>{@code K extends Comparable<K>} in
 *       {@code class TreeMap<K extends Comparable<K>, V>}</li>
 *   <li>{@code R} in {@code public <R> R map(Function<T, R> f)}</li>
 * </ul>
 *
 * <p>Node id format: {@code Owner#<T>}, e.g.
 * {@code com.example.Foo#<T>} for a class-level parameter or
 * {@code com.example.Foo#map(java.util.function.Function)#<R>} for a
 * method-level parameter.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed form of the
 * parameter including bounds, e.g.
 * {@code "T extends Comparable<T> & Serializable"}.
 */
public class TypeParamNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Bounds
    // -------------------------------------------------------------------------

    /**
     * Upper-bound types declared on this type parameter via {@code extends}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code <T>} → empty list (unbounded)</li>
     *   <li>{@code <T extends Foo>} → one entry: {@code FooView}</li>
     *   <li>{@code <T extends Foo & Bar>} → two entries</li>
     * </ul>
     *
     * <p>Populated from {@code HAS_BOUND} outgoing edges.
     * Entries may be stub {@link ClassNodeView}s for external types.
     */
    private final List<ClassNodeView> bounds = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The class or executable that declares this type parameter.
     *
     * <p>Will be a {@link ClassNodeView} for class-level parameters
     * ({@code class Foo<T>}) and a {@link MethodNodeView} for method-level
     * parameters ({@code <R> R map(...)}).
     *
     * <p>Never {@code null} after the graph is wired.
     */
    private GraphNodeView owner;

    public TypeParamNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the upper-bound types for this type parameter
     * (HAS_BOUND outgoing edges).
     *
     * <p>{@code <T extends Foo & Bar>} → [FooView, BarView].
     * Empty list for unbounded parameters.
     *
     * @return list of bound type views; never {@code null}
     */
    public List<ClassNodeView> getBounds() { return bounds; }

    /**
     * Returns the class or method that declares this type parameter
     * (reverse HAS_TYPE_PARAM edge).
     *
     * @return owner view; never {@code null} after wiring
     */
    public GraphNodeView getOwner() { return owner; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    /** Adds {@code bound} to the upper-bound list of this type parameter. */
    void addBound(ClassNodeView bound) { bounds.add(bound); }

    /** Sets the owner (class or executable) of this type parameter. */
    void setOwner(GraphNodeView owner) { this.owner = owner; }
}
