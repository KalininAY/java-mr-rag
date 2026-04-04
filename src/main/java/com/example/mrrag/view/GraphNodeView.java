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
 *      .forEach(m -> m.getCallees().forEach(callee -> ...));
 * }</pre>
 *
 * <p><b>Source content</b> — {@link #getContent()} returns the verbatim original
 * source lines for project nodes, and the Spoon pretty-printed text for
 * external/synthetic nodes (no source file available in the project).
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
        return getClass().getSimpleName() + "(" + getId() + ")\n\n";// + toMarkdown();
    }

    /**
     * Returns a markdown representation of this view suitable for embedding
     * in an LLM prompt.
     *
     * <p>Format:
     * <pre>
     * # Content
     * ### path/to/File.java
     * {startLine}|&lt;original source line&gt;
     * ...
     *
     * # Context
     * ## fieldName
     * ### path/to/File.java
     * {startLine}|&lt;original source line&gt;
     * ...
     * ### external
     * -1|com.example.Foo#bar()
     * -1|com.example.Baz#qux()
     * </pre>
     *
     * <p>Line numbers use real 1-based source positions ({@link #getStartLine()}).
     * {@code startLine == -1} marks external/synthetic nodes — they are grouped
     * under a single {@code ### external} header with {@code -1|<id>} lines,
     * de-duplicated by node ID.
     *
     * @return markdown string; never {@code null}
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        // ── # Content ─────────────────────────────────────────────────────
        sb.append("# Content\n");
        sb.append("### ").append(getFilePath()).append('\n');
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
     * Appends elements of a collection grouped by file path.
     *
     * <p>Elements are collected into an insertion-ordered map:
     * <ul>
     *   <li>External/synthetic views ({@code startLine == -1}) → key {@code "external"}</li>
     *   <li>Project views → key is {@link #getFilePath()}</li>
     *   <li>Non-view scalars → key {@code "values"}</li>
     * </ul>
     *
     * <p>Within each group, duplicate node IDs (for views) or string values
     * (for scalars) are suppressed.
     *
     * <p>Output per group:
     * <pre>
     * ### &lt;filePath | "external" | "values"&gt;
     * {line}|&lt;id or snippet line&gt;
     * ...
     * </pre>
     */
    private static void appendGroupedCollection(StringBuilder sb, Collection<?> col) {
//        boolean addType = col.size() > 1 && col.stream().map(Object::getClass).distinct().count() > 1;

        // group key → ordered set of text lines to emit
        Map<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();

        for (Object element : col) {
            if (element instanceof GraphNodeView view) {
                String key = (view.getStartLine() == -1) ? "external" : view.getFilePath();
//                if (addType)
                    key = "[" + view.node.kind() + "]  " + key;
                LinkedHashSet<String> lines = groups.computeIfAbsent(key, k -> new LinkedHashSet<>());
                appendElementLine(lines, view);
            } else {
                LinkedHashSet<String> lines = groups.computeIfAbsent("values", k -> new LinkedHashSet<>());
                lines.add("1|" + element);
            }
        }

        for (Map.Entry<String, LinkedHashSet<String>> entry : groups.entrySet()) {
            sb.append("### ").append(entry.getKey()).append('\n');
            for (String line : entry.getValue()) {
                sb.append(line).append('\n');
            }
        }
    }

    /**
     * Produces one or more text lines representing {@code view} and adds them
     * to {@code lines} (duplicates are suppressed by the set).
     *
     * <ul>
     *   <li>External view ({@code startLine == -1}): {@code -1|<id>}</li>
     *   <li>Project view with non-blank snippet: numbered source lines
     *       ({@code startLine|text}, …)</li>
     *   <li>Project view without snippet: {@code startLine|<id>} (or {@code 1|<id>}
     *       when startLine ≤ 0)</li>
     * </ul>
     */
    private static void appendElementLine(LinkedHashSet<String> lines, GraphNodeView view) {
        if (view.getStartLine() == -1) {
            // External node: single -1|<id> line
            lines.add("-1|" + view.getId());
            return;
        }
        String snippet = view.getContent();
        if (snippet != null && !snippet.isBlank()) {
            int first = (view.getStartLine() > 0) ? view.getStartLine() : 1;
            String[] snipLines = snippet.split("\n", -1);
            for (int i = 0; i < snipLines.length; i++) {
                lines.add((first + i) + "|" + snipLines[i]);
            }
        } else {
            int lineNo = (view.getStartLine() > 0) ? view.getStartLine() : 1;
            lines.add(lineNo + "|" + view.getId());
        }
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
