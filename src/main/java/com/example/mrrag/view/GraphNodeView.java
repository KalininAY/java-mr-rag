package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.AstGraphService.NodeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all typed node views.
 *
 * <p>Every concrete view holds direct Java references to neighbouring views,
 * so the caller can traverse the graph without any ID look-ups:
 * <pre>{@code
 *   ClassNodeView cls = viewBuilder.classView("com.example.Foo");
 *   cls.getMethods()
 *      .forEach(m -> m.getCallees().forEach(callee -> ...));
 * }</pre>
 *
 * <p>The {@link #getContent()} method returns the pretty-printed source
 * text of the element as produced by Spoon, so every view carries its
 * own source context and can be handed directly to an LLM without
 * further file I/O.
 *
 * <p>The graph is populated by {@link com.example.mrrag.service.GraphViewBuilder}.
 * All list fields are mutable so the builder can add neighbours in two passes
 * (first create all views, then wire references).
 */
public abstract class GraphNodeView {

    private final GraphNode node;

    /** Views that declared this node via a {@code DECLARES} edge (usually one). */
    private final List<GraphNodeView> declaredBy = new ArrayList<>();

    protected GraphNodeView(GraphNode node) {
        this.node = node;
    }

    // ── Core identity ─────────────────────────────────────────────────────────

    /** Unique node id as used in {@link com.example.mrrag.service.AstGraphService.ProjectGraph}. */
    public String getId()         { return node.id(); }

    /** {@link NodeKind} of this node. */
    public NodeKind getKind()     { return node.kind(); }

    /** Simple (unqualified) name. */
    public String getSimpleName() { return node.simpleName(); }

    /** Source-relative file path. */
    public String getFilePath()   { return node.filePath(); }

    /** First line of this element in its source file (1-based). */
    public int getStartLine()     { return node.startLine(); }

    /** Last line of this element in its source file (1-based). */
    public int getEndLine()       { return node.endLine(); }

    /**
     * Pretty-printed source text of this element as produced by Spoon.
     *
     * <p>For classes and methods this is the full declaration including body.
     * For fields and variables it is the single declaration line.
     * For lambdas it is the lambda expression text.
     * External / synthetic stub nodes return an empty string.
     *
     * <p>This value is ready to be embedded in an LLM prompt without
     * additional file I/O.
     */
    public String getContent()    { return node.sourceSnippet(); }

    /** Raw {@link GraphNode} record backing this view. */
    public GraphNode getNode()    { return node; }

    // ── Structural links ──────────────────────────────────────────────────────

    /**
     * The owner that declared this node (e.g. the class that owns a method).
     * Empty list only for top-level classes that have no owning type in the graph.
     */
    public List<GraphNodeView> getDeclaredBy() { return declaredBy; }

    // ── Package-private mutators used by GraphViewBuilder ─────────────────────

    void addDeclaredBy(GraphNodeView owner) { declaredBy.add(owner); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getId() + ")";
    }
}
