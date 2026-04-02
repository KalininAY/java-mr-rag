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

    // ── Core identity ──────────────────────────────────────────────────────────

    /** Unique node id as used in {@link com.example.mrrag.service.AstGraphService.ProjectGraph}. */
    public String getId()         { return node.id(); }

    /** {@link NodeKind} of this node. */
    public NodeKind getKind()     { return node.kind(); }

    /** Simple (unqualified) name. */
    public String getSimpleName() { return node.simpleName(); }

    /** Source-relative file path. */
    public String getFilePath()   { return node.filePath(); }

    /** First line of this element in its source file. */
    public int getStartLine()     { return node.startLine(); }

    /** Last line of this element in its source file. */
    public int getEndLine()       { return node.endLine(); }

    /** Raw {@link GraphNode} record backing this view. */
    public GraphNode getNode()    { return node; }

    // ── Structural links ──────────────────────────────────────────────────────

    /**
     * The owner that declared this node (e.g. the class that owns a method).
     * Empty list only for top-level classes that have no owning type in the graph.
     */
    public List<GraphNodeView> getDeclaredBy() { return declaredBy; }

    // ── Package-private mutators used by GraphViewBuilder ────────────────────

    void addDeclaredBy(GraphNodeView owner) { declaredBy.add(owner); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getId() + ")";
    }
}
