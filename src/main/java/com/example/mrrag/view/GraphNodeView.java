package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.AstGraphService.NodeKind;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

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
     * # Content
     * ### com.example.Foo#bar()
     * {startLine}|&lt;original source line&gt;
     * ...
     *
     * # Context
     * ## fieldName
     * ### [KIND] com.example.SomeClass
     * {startLine}|&lt;declaration line&gt;
     * ...
     * </pre>
     *
     * <p>Project nodes in collections are shown as their declaration line
     * (or full content when declaration is blank).  External/synthetic nodes
     * are rendered as {@code ### [KIND] id Lines:[n1,n2,...]} — a single header
     * line containing all source lines at which they are referenced, derived
     * from the edges stored in the containing view via
     * {@link #collectReferencedLines(GraphNodeView, GraphNodeView)}.
     *
     * <p>Constructor IDs use the canonical {@code #&lt;init&gt;(...)} form.
     *
     * @return markdown string; never {@code null}
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        // ── # Content ──────────────────────────────────────────────────────────
        sb.append("# Content\n");
        sb.append("### ").append(normalizeId(getId())).append('\n');
        appendNumberedSnippet(sb, getContent(), getStartLine());

        // ── # Context ──────────────────────────────────────────────────────────
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
                appendGroupedCollection(sb, col, this);
            } else {
                sb.append("-999|").append(value).append('\n');
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
     * Appends elements of a collection grouped by node ID.
     *
     * <p>For each unique view in the collection:
     * <ul>
     *   <li><b>External node</b> ({@code startLine == -1}): a single header line
     *       {@code ### [KIND] id Lines:[n1,n2,...]} where the line numbers are
     *       collected from outgoing/incoming edges of {@code owner} that reference
     *       this node.</li>
     *   <li><b>Project node</b>: {@code ### [KIND] id} followed by one
     *       {@code startLine|declarationLine} line (or snippet when declaration
     *       is blank).</li>
     *   <li><b>Scalar</b>: emitted as {@code 1|value}.</li>
     * </ul>
     *
     * @param sb     target string builder
     * @param col    collection of elements (may be mixed view / scalar)
     * @param owner  the node whose {@code toMarkdown()} is being rendered;
     *               used to look up edge line numbers for external nodes
     */
    private static void appendGroupedCollection(StringBuilder sb,
                                                Collection<?> col,
                                                GraphNodeView owner) {
        // Preserve insertion order; key = normalised node id (or "values" for scalars)
        // value = list of text lines to emit under the ### header
        Map<String, List<String>> sections = new LinkedHashMap<>();

        for (Object element : col) {
            if (element instanceof GraphNodeView view) {
                String id = normalizeId(view.getId());
                String key = "[" + view.node.kind() + "] " + id;
                if (!sections.containsKey(key)) {
                    sections.put(key, new ArrayList<>());
                }
                List<String> bodyLines = sections.get(key);

                if (view.getStartLine() == -1) {
                    // External node: body is empty — all info goes into the header via Lines:[...]
                    // (lines are appended to the header later; here we only ensure the key exists)
                } else {
                    // Project node: emit declaration (or snippet) line(s)
                    appendProjectElementLines(bodyLines, view);
                }
            } else {
                sections.computeIfAbsent("values", k -> new ArrayList<>())
                        .add("1|" + element);
            }
        }

        // Now render sections.  For external nodes, collect referenced line numbers.
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            String sectionKey = entry.getKey();
            List<String> bodyLines = entry.getValue();

            // Detect external node sections: they have no body lines collected above
            // and the key starts with "["
            if (bodyLines.isEmpty() && sectionKey.startsWith("[")) {
                // Reconstruct the view to collect line numbers
                // We must find the matching view from the original collection
                String linesAnnotation = buildLinesAnnotation(col, sectionKey, owner);
                sb.append("### ").append(sectionKey).append(linesAnnotation).append('\n');
            } else {
                sb.append("### ").append(sectionKey).append('\n');
                for (String line : bodyLines) {
                    sb.append(line).append('\n');
                }
            }
        }
    }

    /**
     * Builds a {@code " Lines:[n1,n2,...]"} suffix for an external node by scanning
     * all edges of {@code owner} that reference the node identified by {@code sectionKey}.
     *
     * <p>{@code sectionKey} has the form {@code "[KIND] normalizedId"}. We strip the
     * kind prefix to recover the raw (normalised) id and match it against the view
     * entries in {@code col}.
     */
    private static String buildLinesAnnotation(Collection<?> col,
                                               String sectionKey,
                                               GraphNodeView owner) {
        // Extract normalised id from section key like "[METHOD] com.example.Foo#bar()"
        int spaceIdx = sectionKey.indexOf("] ");
        if (spaceIdx < 0) return "";
        String normalizedId = sectionKey.substring(spaceIdx + 2);

        // Find the matching view in the collection
        GraphNodeView targetView = null;
        for (Object element : col) {
            if (element instanceof GraphNodeView v
                    && normalizeId(v.getId()).equals(normalizedId)) {
                targetView = v;
                break;
            }
        }
        if (targetView == null) return "";

        // Collect all edge line numbers from owner that point to / from this view
        String rawId = targetView.getId();
        List<Integer> lineNumbers = collectReferencedLines(owner, rawId);
        if (lineNumbers.isEmpty()) return "";

        String joined = lineNumbers.stream()
                .sorted()
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return " Lines:[" + joined + "]";
    }

    /**
     * Collects source-line numbers of all edges that connect {@code owner}
     * (in either direction) to the node with the given raw {@code targetId}.
     *
     * <p>Requires that the owner view exposes its graph edges somehow.  Because
     * {@link GraphNodeView} itself does not hold edge lists, we inspect concrete
     * subclass fields for {@code Collection<GraphNodeView>} members and match
     * their elements.  The actual line numbers are retrieved from the backing
     * {@link com.example.mrrag.service.AstGraphService.GraphEdge} records via
     * the subclass's own edge-line maps when available; otherwise we fall back
     * to the target view's {@code startLine}.
     *
     * <p><b>Default implementation</b> — returns an empty list.  Subclasses that
     * carry edge metadata (e.g. a {@code Map<String,List<Integer>> calleeLines})
     * should override {@link #edgeLinesTo(String)} to provide the real data.
     *
     * @param owner    the node whose markdown is being rendered
     * @param targetId raw (non-normalised) node id of the referenced node
     * @return mutable list of line numbers; may be empty
     */
    private static List<Integer> collectReferencedLines(GraphNodeView owner, String targetId) {
        return owner.edgeLinesTo(targetId);
    }

    /**
     * Returns the source-line numbers at which this node's outgoing edges
     * reference the node identified by {@code targetId}.
     *
     * <p>The default implementation returns an empty list.  Concrete subclasses
     * that store edge-line metadata (e.g.
     * {@code Map<GraphNodeView, List<Integer>> calleeLinesMap}) should override
     * this method to expose that data.
     *
     * @param targetId raw node id of the target
     * @return line numbers; may be empty
     */
    protected List<Integer> edgeLinesTo(String targetId) {
        return List.of();
    }

    /**
     * Adds declaration line(s) of a project node to {@code lines}.
     *
     * <ul>
     *   <li>Non-blank declaration snippet → numbered declaration lines</li>
     *   <li>No declaration but has snippet → first source line</li>
     *   <li>Neither → {@code startLine|id} fallback</li>
     * </ul>
     */
    private static void appendProjectElementLines(List<String> lines, GraphNodeView view) {
        String decl = view.getDeclaration();
        if (decl != null && !decl.isBlank()) {
            int first = Math.max(1, view.getStartLine());
            for (String dl : decl.split("\n", -1)) {
                lines.add(first + "|" + dl);
                first++;
            }
            return;
        }
        String snippet = view.getContent();
        if (snippet != null && !snippet.isBlank()) {
            int first = Math.max(1, view.getStartLine());
            String firstLine = snippet.split("\n", 2)[0];
            lines.add(first + "|" + firstLine);
            return;
        }
        int lineNo = Math.max(1, view.getStartLine());
        lines.add(lineNo + "|" + normalizeId(view.getId()));
    }

    /**
     * Normalises a node ID to the canonical form used in markdown output.
     *
     * <p>Currently: replaces constructor IDs of the form
     * {@code pkg.ClassName#ClassName(params)} with
     * {@code pkg.ClassName#<init>(params)}.
     *
     * @param id raw node id
     * @return normalised id
     */
    public static String normalizeId(String id) {
        if (id == null) return "";
        // Match "Owner#SimpleName(...)" where SimpleName equals the last segment of Owner
        // e.g. "java.util.concurrent.atomic.AtomicInteger#AtomicInteger(int)"
        //   -> "java.util.concurrent.atomic.AtomicInteger#<init>(int)"
        int hash = id.indexOf('#');
        if (hash < 0) return id;
        String owner = id.substring(0, hash);          // e.g. "pkg.ClassName"
        String rest  = id.substring(hash + 1);         // e.g. "ClassName(int)"
        int dot = owner.lastIndexOf('.');
        String simpleName = dot >= 0 ? owner.substring(dot + 1) : owner;
        // rest starts with simpleName followed by '(' → constructor
        if (rest.startsWith(simpleName + "(")) {
            return owner + "#<init>(" + rest.substring(simpleName.length() + 1);
        }
        return id;
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
