package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.AstGraphService.NodeKind;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Abstract base for all typed node views in the symbol graph.
 *
 * <p>Every concrete subclass holds <em>direct Java references</em> to neighbouring
 * views, so callers can traverse the full graph without any ID look-ups:
 * <pre>{@code
 *   ClassNodeView cls = viewBuilder.classView("com.example.Foo");
 *   cls.getMethods()
 *      .forEach(m -> m.getCallees().forEach(ref -> ...));
 * }</pre>
 *
 * <p><b>Source content</b> — {@link #getContent()} returns the verbatim original
 * source lines for project nodes, and the Spoon pretty-printed text for
 * external/synthetic nodes (no source file available in the project).
 *
 * <p><b>Declaration</b> — {@link #getDeclaration()} returns only the declaration
 * header (annotations, modifiers, name, parameters, throws-clause,
 * extends/implements, up to and including the opening {@code {}), without the body.
 * For FIELD nodes the full field line is the declaration. Empty for LAMBDA,
 * VARIABLE, TYPE_PARAM, and ANNOTATION_ATTRIBUTE nodes.
 *
 * <p><b>Edge collections</b> — fields typed as {@code List<EdgeRef>} (callers,
 * callees, readsFields, etc.) store both the neighbouring view and the
 * source line of the edge so that {@link #toMarkdown()} can emit a single
 * contextual line instead of a full declaration block.
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
     * @return unique node id; never {@code null}
     */
    public String getId()         { return node.id(); }

    /**
     * Returns the {@link NodeKind} of this node.
     *
     * @return node kind; never {@code null}
     */
    public NodeKind getKind()     { return node.kind(); }

    /**
     * Returns the simple (unqualified) name of this element.
     *
     * @return simple name; never {@code null}
     */
    public String getSimpleName() { return node.simpleName(); }

    /**
     * Returns the source-relative path of the file that contains this element.
     * Returns {@code "unknown"} for external / synthetic elements.
     *
     * @return relative file path; never {@code null}
     */
    public String getFilePath()   { return node.filePath(); }

    /**
     * Returns the first source line of this element (1-based).
     * Returns {@code -1} for external/synthetic nodes that have no source file
     * in this project.
     *
     * @return start line number, or {@code -1}
     */
    public int getStartLine()     { return node.startLine(); }

    /**
     * Returns the last source line of this element (1-based).
     * Returns {@code -1} for external/synthetic nodes.
     *
     * @return end line number, or {@code -1}
     */
    public int getEndLine()       { return node.endLine(); }

    /**
     * Returns the source text of this element.
     * For project nodes this is verbatim original source lines.
     * For external/synthetic nodes this is Spoon pretty-printed text.
     *
     * @return source snippet; never {@code null}, may be empty
     */
    public String getContent()    { return node.sourceSnippet(); }

    /**
     * Returns the declaration header of this element — the part before the body.
     *
     * <p>For CLASS/INTERFACE/METHOD/CONSTRUCTOR nodes this is the verbatim source
     * lines from {@link #getStartLine()} up to and including the first opening
     * {@code {} (annotations, modifiers, name, parameters, throws-clause,
     * extends/implements).
     * For FIELD nodes this is the full field declaration line.
     * For LAMBDA, VARIABLE, TYPE_PARAM, and ANNOTATION_ATTRIBUTE nodes this is
     * an empty string.
     *
     * @return declaration header; never {@code null}, may be empty
     */
    public String getDeclaration() { return node.declarationSnippet(); }

    /**
     * Returns the raw {@link GraphNode} record that backs this view.
     *
     * @return backing graph node; never {@code null}
     */
    public GraphNode getNode()    { return node; }

    // -------------------------------------------------------------------------
    // Structural links
    // -------------------------------------------------------------------------

    public List<GraphNodeView> getDeclaredBy() { return declaredBy; }
    public List<ClassNodeView> getAnnotatedBy() { return annotatedBy; }

    public void addDeclaredBy(GraphNodeView owner) { declaredBy.add(owner); }
    public void addAnnotatedBy(ClassNodeView annotation) { annotatedBy.add(annotation); }

    // -------------------------------------------------------------------------
    // toString + toMarkdown
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getId() + ")\n\n";
    }

    /**
     * Returns a markdown representation of this view suitable for embedding
     * in an LLM prompt.
     *
     * <p>Format:
     * <pre>
     * # Declaration
     * ### &lt;nodeId&gt;
     * {startLine}|&lt;declaration header line&gt;
     * ...
     *
     * # Content
     * ### &lt;nodeId&gt;
     * {startLine}|&lt;original source line&gt;
     * ...
     *
     * # Context
     * ## fieldName
     * ### [KIND] &lt;nodeId&gt;
     * {line}|&lt;source line at edge site, or id for plain view refs&gt;
     * ...
     * </pre>
     *
     * <p>The {@code # Declaration} section is omitted when
     * {@link #getDeclaration()} is blank (LAMBDA, VARIABLE, TYPE_PARAM,
     * ANNOTATION_ATTRIBUTE nodes).
     *
     * <p>Context rendering rules:
     * <ul>
     *   <li>{@link EdgeRef} entries — header is {@code [KIND] <nodeId>};
     *       body is the single source line at the edge's recorded line number
     *       extracted from the view's content snippet, or {@code line|id} when
     *       the line cannot be resolved.</li>
     *   <li>Plain {@link GraphNodeView} entries (e.g. {@code declaredBy},
     *       {@code annotatedBy}) — header is {@code [KIND] <nodeId>};
     *       no body lines (the id in the header is sufficient).</li>
     *   <li>Scalar values — rendered as {@code 1|value}.</li>
     * </ul>
     *
     * @return markdown string; never {@code null}
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        // ── # Declaration ────────────────────────────────────────────────────
        String decl = getDeclaration();
        if (decl != null && !decl.isBlank()) {
            sb.append("# Declaration\n");
            sb.append("### ").append(getId()).append('\n');
            appendNumberedSnippet(sb, decl, getStartLine());
            sb.append('\n');
        }

        // ── # Content ─────────────────────────────────────────────────────
        sb.append("# Content\n");
        sb.append("### ").append(getId()).append('\n');
        appendNumberedSnippet(sb, getContent(), getStartLine());

        // ── # Context ─────────────────────────────────────────────────────
        sb.append("\n# Context\n");
        for (Field field : collectFields(getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(this);
            } catch (IllegalAccessException e) {
                value = "<inaccessible>";
            }

            sb.append("## ").append(field.getName()).append('\n');

            if (value == null) {
                sb.append("0|(null)\n");
            } else if (value instanceof Collection<?> col) {
                appendGroupedCollection(sb, col);
            } else {
                // Single view reference (e.g. declaredByClass, overrides)
                if (value instanceof GraphNodeView view) {
                    sb.append("### [").append(view.getKind()).append("] ")
                      .append(view.getId()).append('\n');
                } else {
                    sb.append("-999|").append(value).append('\n');
                }
            }
        }

        return sb.toString();
    }

    /**
     * Appends a line-numbered snippet to {@code sb}.
     *
     * <ul>
     *   <li>{@code startLine == -1} — external/synthetic node: lines numbered
     *       {@code -1}, {@code -2}, … so they are visually distinct from real source</li>
     *   <li>{@code startLine > 0}  — project node: real line numbers starting at startLine</li>
     *   <li>blank/null snippet    — emits {@code 0|(empty)}</li>
     * </ul>
     */
    private static void appendNumberedSnippet(StringBuilder sb, String snippet, int startLine) {
        if (startLine == -1) {
            if (snippet == null || snippet.isBlank()) {
                sb.append("-1|(external)\n");
                return;
            }
            String[] lines = snippet.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(-(i + 1)).append('|').append(lines[i]).append('\n');
            }
            return;
        }
        if (snippet == null || snippet.isBlank()) {
            sb.append("0|(empty)\n");
            return;
        }
        String[] lines = snippet.split("\n", -1);
        int first = (startLine > 0) ? startLine : 1;
        for (int i = 0; i < lines.length; i++) {
            sb.append(first + i).append('|').append(lines[i]).append('\n');
        }
    }

    /**
     * Appends elements of a collection grouped by node id.
     *
     * <p>Three element types are handled:
     * <ul>
     *   <li>{@link EdgeRef} — grouped under {@code [KIND] <nodeId>}; each group
     *       body contains the single source line at the recorded edge line
     *       extracted from the view's snippet.  Multiple refs to the same node
     *       emit one entry per distinct call-site line.</li>
     *   <li>{@link GraphNodeView} — grouped under {@code [KIND] <nodeId>};
     *       no body lines emitted (the header is the full information).
     *       Duplicate node ids are suppressed.</li>
     *   <li>Scalar — rendered under key {@code "values"} as {@code 1|value}.</li>
     * </ul>
     */
    private static void appendGroupedCollection(StringBuilder sb, Collection<?> col) {
        // group key → ordered list of body lines (may be empty for plain views)
        Map<String, List<String>> groups = new LinkedHashMap<>();

        for (Object element : col) {
            if (element instanceof EdgeRef ref) {
                GraphNodeView view = ref.view();
                String key = "[" + view.getKind() + "] " + view.getId();
                List<String> lines = groups.computeIfAbsent(key, k -> new ArrayList<>());
                String bodyLine = extractLineFromView(view, ref.line());
                if (!lines.contains(bodyLine)) {
                    lines.add(bodyLine);
                }
            } else if (element instanceof GraphNodeView view) {
                String key = "[" + view.getKind() + "] " + view.getId();
                // Plain view: no body — just register the group key once
                groups.computeIfAbsent(key, k -> new ArrayList<>());
            } else {
                List<String> lines = groups.computeIfAbsent("values", k -> new ArrayList<>());
                String entry = "1|" + element;
                if (!lines.contains(entry)) lines.add(entry);
            }
        }

        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            sb.append("### ").append(entry.getKey()).append('\n');
            for (String line : entry.getValue()) {
                sb.append(line).append('\n');
            }
        }
    }

    /**
     * Extracts the single source line at {@code edgeLine} from the view's
     * content snippet.
     *
     * <p>The snippet is stored with lines from {@code view.getStartLine()} to
     * {@code view.getEndLine()}, so the offset into the array is
     * {@code edgeLine - view.getStartLine()}.
     *
     * <p>Falls back to {@code edgeLine|<id>} when:
     * <ul>
     *   <li>{@code edgeLine} is {@code -1} (external/synthetic edge)</li>
     *   <li>the computed offset is out of bounds</li>
     *   <li>the snippet is blank</li>
     * </ul>
     *
     * @param view     the neighbouring node whose snippet is searched
     * @param edgeLine 1-based source line of the edge; {@code -1} if unknown
     * @return a single formatted line, e.g. {@code "72|        placeFrom = findPlaceFrom();"}
     */
    private static String extractLineFromView(GraphNodeView view, int edgeLine) {
        if (edgeLine == -1) {
            return "-1|" + view.getId();
        }
        String snippet = view.getContent();
        int startLine  = view.getStartLine();
        if (snippet != null && !snippet.isBlank() && startLine > 0) {
            String[] lines = snippet.split("\n", -1);
            int offset = edgeLine - startLine;
            if (offset >= 0 && offset < lines.length) {
                return edgeLine + "|" + lines[offset];
            }
        }
        // Fallback: line number + id
        return edgeLine + "|" + view.getId();
    }

    /**
     * Collects all declared fields from {@code clazz} up to (but not including)
     * {@link Object}, skipping the {@code node} backing field.
     */
    private static List<Field> collectFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getName().equals("node")) {
                    result.add(f);
                }
            }
        }
        return result;
    }
}
