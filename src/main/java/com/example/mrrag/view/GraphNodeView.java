package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.AstGraphService.NodeKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all typed node views in the symbol graph.
 *
 * <p>Every concrete subclass holds <em>direct Java references</em> to neighbouring
 * views, so callers can traverse the full graph without any ID look-ups:
 * <pre>{@code
 *   ClassNodeView cls = viewBuilder.classView("com.example.Foo");
 *   cls.getMethods()
 *      .forEach(m -> m.getCallees().forEach(callee -> ...));
 * }</pre>
 *
 * <p><b>Source content</b> — {@link #getContent()} returns the pretty-printed
 * source text of the element as produced by Spoon, so every view carries its
 * own source context and can be handed directly to an LLM without further
 * file I/O.
 *
 * <p><b>Wiring</b> — all list fields are mutable so
 * {@link com.example.mrrag.service.GraphViewBuilder} can populate neighbours
 * in two passes: first create all views, then wire references.
 *
 * <p><b>Concurrency</b> — view objects are <em>not</em> thread-safe after
 * construction; they are intended to be built once and then used read-only.
 */
public abstract class GraphNodeView {

    /** The raw graph node that backs this view. Never {@code null}. */
    private final GraphNode node;

    /**
     * Nodes that declared this node via a {@code DECLARES} edge.
     * For most nodes this list contains exactly one entry (the owner class or
     * executable).  Top-level classes with no owning type in the graph have
     * an empty list.
     */
    private final List<GraphNodeView> declaredBy = new ArrayList<>();

    /**
     * Annotation types ({@link ClassNodeView}) that annotate this node via
     * {@code ANNOTATED_WITH} outgoing edges.
     *
     * <p>Example: if the method is declared as
     * {@code @Override @Transactional public void save(...)}, this list
     * contains the {@link ClassNodeView} entries for {@code Override} and
     * {@code Transactional} (if present in the graph).
     *
     * <p>Populated from {@code ANNOTATED_WITH} outgoing edges by
     * {@link com.example.mrrag.service.GraphViewBuilder}.
     */
    private final List<ClassNodeView> annotatedBy = new ArrayList<>();

    /**
     * Constructs a view wrapping the given raw node.
     *
     * @param node the raw {@link GraphNode} record; must not be {@code null}
     */
    protected GraphNodeView(GraphNode node) {
        this.node = node;
    }

    // -------------------------------------------------------------------------
    // Core identity
    // -------------------------------------------------------------------------

    /**
     * Returns the unique node identifier as stored in
     * {@link com.example.mrrag.service.AstGraphService.ProjectGraph}.
     *
     * <p>Format depends on node kind:
     * <ul>
     *   <li>CLASS — fully qualified name, e.g. {@code com.example.Foo}</li>
     *   <li>METHOD / CONSTRUCTOR — {@code Owner#signature}, e.g.
     *       {@code com.example.Foo#doWork(int)}</li>
     *   <li>FIELD — {@code Owner.fieldName}, e.g. {@code com.example.Foo.count}</li>
     *   <li>VARIABLE — {@code var@FileName.java:line:name}</li>
     *   <li>LAMBDA — {@code lambda@relPath:line}</li>
     *   <li>TYPE_PARAM — {@code Owner#<T>}</li>
     *   <li>ANNOTATION_ATTRIBUTE — {@code AnnotationType#attrName}</li>
     * </ul>
     *
     * @return unique node id; never {@code null}
     */
    public String getId()         { return node.id(); }

    /**
     * Returns the {@link NodeKind} of this node, e.g. {@code CLASS},
     * {@code METHOD}, {@code TYPE_PARAM}.
     *
     * @return node kind; never {@code null}
     */
    public NodeKind getKind()     { return node.kind(); }

    /**
     * Returns the simple (unqualified) name of this element.
     *
     * <p>Examples: {@code "Foo"} for a class, {@code "doWork"} for a method,
     * {@code "count"} for a field, {@code "T"} for a type parameter,
     * {@code "λ"} for a lambda.
     *
     * @return simple name; never {@code null}
     */
    public String getSimpleName() { return node.simpleName(); }

    /**
     * Returns the source-relative path of the file that contains this element,
     * e.g. {@code src/main/java/com/example/Foo.java}.
     *
     * <p>Returns {@code "unknown"} for external / synthetic elements.
     *
     * @return relative file path; never {@code null}
     */
    public String getFilePath()   { return node.filePath(); }

    /**
     * Returns the first source line of this element (1-based).
     * Returns {@code 0} when position information is unavailable.
     *
     * @return start line number
     */
    public int getStartLine()     { return node.startLine(); }

    /**
     * Returns the last source line of this element (1-based).
     * Returns {@code 0} when position information is unavailable.
     *
     * @return end line number
     */
    public int getEndLine()       { return node.endLine(); }

    /**
     * Returns the pretty-printed source text of this element as produced
     * by Spoon's pretty-printer.
     *
     * <p>Content by node kind:
     * <ul>
     *   <li>CLASS — full type declaration including body</li>
     *   <li>METHOD / CONSTRUCTOR — signature and body</li>
     *   <li>FIELD — single field declaration line</li>
     *   <li>VARIABLE — variable declaration statement</li>
     *   <li>LAMBDA — lambda expression text</li>
     *   <li>TYPE_PARAM — parameter with bounds, e.g.
     *       {@code "T extends Comparable<T> & Serializable"}</li>
     *   <li>ANNOTATION_ATTRIBUTE — method declaration with default clause</li>
     * </ul>
     *
     * <p>External and synthetic stub nodes return an empty string.
     * This value is ready to embed directly in an LLM prompt without
     * additional file I/O.
     *
     * @return source snippet; never {@code null}, may be empty
     */
    public String getContent()    { return node.sourceSnippet(); }

    /**
     * Returns the raw {@link GraphNode} record that backs this view.
     * Prefer the typed getters above; use this only when low-level access
     * to the record is required.
     *
     * @return backing graph node; never {@code null}
     */
    public GraphNode getNode()    { return node; }

    // -------------------------------------------------------------------------
    // Structural links
    // -------------------------------------------------------------------------

    /**
     * Returns the nodes that declared this node via a {@code DECLARES} edge.
     *
     * <p>Typical cardinality:
     * <ul>
     *   <li>1 — for methods, fields, constructors, lambdas (one owning class or
     *       executable)</li>
     *   <li>0 — for top-level classes with no enclosing type in the graph</li>
     * </ul>
     *
     * @return immutable-safe list of declaring nodes; never {@code null}
     */
    public List<GraphNodeView> getDeclaredBy() { return declaredBy; }

    /**
     * Returns the annotation types applied to this node via
     * {@code ANNOTATED_WITH} outgoing edges.
     *
     * <p>Each entry is the {@link ClassNodeView} of the annotation type
     * (e.g. the view for {@code @Transactional} or {@code @Override}).
     * The list is empty when no annotations are recorded in the graph for
     * this node.
     *
     * @return immutable-safe list of annotation type views; never {@code null}
     */
    public List<ClassNodeView> getAnnotatedBy() { return annotatedBy; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    /** Registers {@code owner} as a declaring node of this view. */
    public void addDeclaredBy(GraphNodeView owner) { declaredBy.add(owner); }

    /**
     * Registers {@code annotation} as an annotation type applied to this node.
     *
     * <p>Called by {@link com.example.mrrag.service.GraphViewBuilder} when
     * wiring {@code ANNOTATED_WITH} edges.
     *
     * @param annotation the {@link ClassNodeView} of the annotation type;
     *                   must not be {@code null}
     */
    public void addAnnotatedBy(ClassNodeView annotation) { annotatedBy.add(annotation); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getId() + ")";
    }
}
