package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#TYPE_PARAM} node.
 *
 * <p>Represents a generic type parameter declaration such as
 * {@code T} in {@code class Foo<T>} or
 * {@code K extends Comparable<K>} in a method signature.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed form of the
 * parameter, e.g. {@code "T extends Comparable<T> & Serializable"}.
 */
public class TypeParamNodeView extends GraphNodeView {

    /**
     * Upper-bound types declared on this type parameter via {@code extends}.
     * {@code T extends Comparable<T> & Serializable} → two stubs in this list.
     * Empty for an unbounded parameter ({@code T}).
     */
    private final List<ClassNodeView> bounds = new ArrayList<>();

    /** The class or executable that owns this type parameter. */
    private GraphNodeView owner;

    TypeParamNodeView(GraphNode node) {
        super(node);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Upper-bound types for this type parameter.
     * {@code <T extends Foo & Bar>} → [FooView, BarView]
     */
    public List<ClassNodeView> getBounds() { return bounds; }

    /**
     * The class or method that declares this type parameter.
     * Never {@code null} after the graph is wired.
     */
    public GraphNodeView getOwner() { return owner; }

    // ── Package-private mutators used by GraphViewBuilder ─────────────────────

    void addBound(ClassNodeView bound)  { bounds.add(bound); }
    void setOwner(GraphNodeView owner)  { this.owner = owner; }
}
