package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.AstGraphService.NodeKind;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;
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
 * For FIELD nodes the full field declaration line is the declaration. Empty for LAMBDA,
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
     * For METHOD/CONSTRUCTOR/LAMBDA nodes: maps caller id → source lines where that
     * caller invokes <em>this</em> node (reverse of INVOKES). Used by {@link #toMarkdown()}.
     */
    private Map<String, List<Integer>> callerSiteLinesMap;

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

    /**
     * Records a source line at which {@code callerId} invokes this callable.
     * Populated from reverse INVOKES wiring in {@link com.example.mrrag.service.GraphViewBuilder}.
     */
    public void recordCallerInvocationSite(String callerId, int line) {
        if (callerId == null || line <= 0) return;
        if (callerSiteLinesMap == null) {
            callerSiteLinesMap = new HashMap<>();
        }
        callerSiteLinesMap.computeIfAbsent(callerId, k -> new ArrayList<>()).add(line);
    }

    /**
     * Source lines where the given caller invokes this node; empty if unknown.
     */
    protected List<Integer> callerSiteLinesFrom(String callerId) {
        if (callerSiteLinesMap == null) {
            return List.of();
        }
        return callerSiteLinesMap.getOrDefault(callerId, List.of());
    }

    /**
     * Outgoing INVOKES targets; empty unless this node maintains a call graph.
     */
    protected List<GraphNodeView> outgoingCallees() {
        return List.of();
    }

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
     * {@link #collectReferencedLines(GraphNodeView, String)}.
     *
     * <p>Constructor IDs use the canonical {@code #&lt;init&gt;(...)} form.
     * {@code callers} lists show the invocation line inside each caller's body when known.
     * {@code instantiates} prefers the matching {@code CONSTRUCTOR} node when an INVOKES edge
     * shares the same source line as {@code new}. Scalar fields that are themselves
     * {@link GraphNodeView} instances are rendered the same way as single-element collections.
     *
     * @return markdown string; never {@code null}
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();

        // ── # Content ───────────────────────────────────────────────────────
        sb.append("# Content\n");
        sb.append("### ").append(normalizeId(getId())).append('\n');
        appendNumberedSnippet(sb, getContent(), getStartLine());

        // ── # Context ──────────────────────────────────────────────────────
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
                // null scalar field — skip silently (no output line)
            } else if (value instanceof Collection<?> col) {
                appendGroupedCollection(sb, col, this, field.getName());
            } else if (value instanceof GraphNodeView view) {
                // Scalar GraphNodeView field: render identically to a single-element collection
                appendGroupedCollection(sb, List.of(view), this, field.getName());
            } else if (value instanceof Map) {
                // Internal edge-line maps: skip from markdown output
            } else {
                sb.append("1|").append(value).append('\n');
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
     *   <li><b>Project node</b>: {@code ### [KIND] id} optionally with
     *       {@code Lines:[n1,n2,...]} when the collection field records outgoing
     *       edges (e.g. {@code callees}), then one or more
     *       {@code startLine|declarationLine} lines (or snippet when declaration
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
                                                GraphNodeView owner,
                                                String contextFieldName) {
        // Preserve insertion order; key = normalised node id (or "values" for scalars)
        // value = list of text lines to emit under the ### header
        Map<String, List<String>> sections = new LinkedHashMap<>();

        for (Object element : col) {
            if (element instanceof GraphNodeView view) {
                GraphNodeView displayView = view;
                if ("instantiates".equals(contextFieldName) && view instanceof ClassNodeView cls) {
                    GraphNodeView ctor = resolveInstantiationConstructor(owner, cls);
                    if (ctor != null) {
                        displayView = ctor;
                    }
                }

                String id = normalizeId(displayView.getId());
                String key = "[" + displayView.node.kind() + "] " + id;
                if (!sections.containsKey(key)) {
                    sections.put(key, new ArrayList<>());
                }
                List<String> bodyLines = sections.get(key);

                if (displayView.getStartLine() == -1) {
                    // External node: body is empty — all info goes into the header via Lines:[...]
                } else {
                    // Project node: emit declaration (or snippet) line(s). Same callee may appear
                    // multiple times (different call sites); emit the declaration block only once.
                    if (bodyLines.isEmpty()) {
                        appendProjectElementLines(bodyLines, displayView, owner, contextFieldName);
                    }
                }
            } else {
                sections.computeIfAbsent("values", k -> new ArrayList<>())
                        .add("1|" + element);
            }
        }

        // Now render sections. External nodes: Lines:[...] only in the header.
        // Project nodes in outgoing-edge fields (callees, readsFields, …): Lines:[...] on the header too.
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            String sectionKey = entry.getKey();
            List<String> bodyLines = entry.getValue();

            String linesAnnotation = "";
            if (sectionKey.startsWith("[")) {
                if (bodyLines.isEmpty()) {
                    linesAnnotation = buildLinesAnnotation(col, sectionKey, owner, contextFieldName);
                } else if (includeOutgoingLinesInHeader(contextFieldName)) {
                    linesAnnotation = buildLinesAnnotation(col, sectionKey, owner, contextFieldName);
                }
            }
            sb.append("### ").append(sectionKey).append(linesAnnotation).append('\n');
            for (String line : bodyLines) {
                sb.append(line).append('\n');
            }
        }
    }

    /**
     * Context fields where {@code owner} records {@link #edgeLinesTo(String)} for each target
     * — show {@code Lines:[...]} on the {@code ###} header even for project nodes.
     */
    private static boolean includeOutgoingLinesInHeader(String contextFieldName) {
        return switch (contextFieldName) {
            case "callees", "referencedMethods", "instantiates", "instantiatesAnon",
                 "readsFields", "writesFields", "throwsTypes", "referencesTypes" -> true;
            default -> false;
        };
    }

    /**
     * Builds a {@code " Lines:[n1,n2,...]"} suffix for an external node by scanning
     * all edges of {@code owner} that reference the node identified by {@code sectionKey}.
     */
    private static String buildLinesAnnotation(Collection<?> col,
                                               String sectionKey,
                                               GraphNodeView owner,
                                               String contextFieldName) {
        int spaceIdx = sectionKey.indexOf("] ");
        if (spaceIdx < 0) return "";
        String normalizedId = sectionKey.substring(spaceIdx + 2);

        GraphNodeView targetView = null;
        for (Object element : col) {
            if (element instanceof GraphNodeView v) {
                GraphNodeView match = v;
                if ("instantiates".equals(contextFieldName) && v instanceof ClassNodeView cls) {
                    GraphNodeView ctor = resolveInstantiationConstructor(owner, cls);
                    if (ctor != null) {
                        match = ctor;
                    }
                }
                if (normalizeId(match.getId()).equals(normalizedId)) {
                    targetView = match;
                    break;
                }
            }
        }
        if (targetView == null) return "";

        List<Integer> lineNumbers = collectReferencedLines(owner, targetView.getId());
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
     * to the node with the given raw {@code targetId}.
     * Delegates to {@link #edgeLinesTo(String)} which subclasses override.
     */
    private static List<Integer> collectReferencedLines(GraphNodeView owner, String targetId) {
        return owner.edgeLinesTo(targetId);
    }

    /**
     * Returns the source-line numbers at which this node's outgoing edges
     * reference the node identified by {@code targetId}.
     *
     * <p>The default implementation returns an empty list.  Concrete subclasses
     * that store edge-line metadata should override this method.
     *
     * @param targetId raw node id of the target
     * @return line numbers; may be empty
     */
    protected List<Integer> edgeLinesTo(String targetId) {
        return List.of();
    }

    /**
     * If {@code owner} instantiates {@code cls} via {@code new} on the same line as an INVOKES
     * edge to a constructor, returns that constructor view for markdown headers/snippets.
     */
    private static GraphNodeView resolveInstantiationConstructor(GraphNodeView owner, ClassNodeView cls) {
        List<Integer> classLines = owner.edgeLinesTo(cls.getId());
        if (classLines.isEmpty()) {
            return null;
        }
        Set<Integer> classLineSet = new HashSet<>(classLines);
        String classId = cls.getId();
        for (GraphNodeView c : owner.outgoingCallees()) {
            if (c.getKind() != NodeKind.CONSTRUCTOR) {
                continue;
            }
            String nid = normalizeId(c.getId());
            if (!nid.startsWith(classId + "#")) {
                continue;
            }
            for (int L : owner.edgeLinesTo(c.getId())) {
                if (classLineSet.contains(L)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * First source line that contains a type declaration keyword (after annotations / javadoc).
     */
    private static final Pattern TYPE_DECLARATION_LINE =
            Pattern.compile("\\b(class|interface|enum|record|@interface)\\b");

    private static int lineIndexOfTypeDeclaration(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (TYPE_DECLARATION_LINE.matcher(lines[i]).find()) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isTypeNodeKind(NodeKind k) {
        return k == NodeKind.CLASS || k == NodeKind.INTERFACE || k == NodeKind.ANNOTATION;
    }

    private static String physicalLineContent(GraphNodeView view, int physicalLine) {
        if (physicalLine <= 0 || view.getStartLine() <= 0) {
            return null;
        }
        String content = view.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] ls = content.split("\n", -1);
        int idx = physicalLine - view.getStartLine();
        if (idx < 0 || idx >= ls.length) {
            return null;
        }
        return ls[idx];
    }

    /**
     * Adds declaration line(s) of a project node to {@code lines}.
     */
    private static void appendProjectElementLines(List<String> lines,
                                                  GraphNodeView view,
                                                  GraphNodeView owner,
                                                  String contextFieldName) {
        if ("callers".equals(contextFieldName)) {
            List<Integer> sites = owner.callerSiteLinesFrom(view.getId());
            if (!sites.isEmpty()) {
                boolean any = false;
                for (int L : sites.stream().sorted().distinct().toList()) {
                    String callLine = physicalLineContent(view, L);
                    if (callLine != null) {
                        lines.add(L + "|" + callLine);
                        any = true;
                    }
                }
                if (any) {
                    return;
                }
            }
        }

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
            String[] contentLines = snippet.split("\n", -1);
            int skip = 0;
            if (isTypeNodeKind(view.getKind())) {
                skip = lineIndexOfTypeDeclaration(contentLines);
            }
            int first = Math.max(1, view.getStartLine()) + skip;
            String firstLine = contentLines[skip];
            lines.add(first + "|" + firstLine);
            return;
        }
        int lineNo = Math.max(1, view.getStartLine());
        lines.add(lineNo + "|" + normalizeId(view.getId()));
    }

    /**
     * Normalises a node ID to the canonical {@code #<init>(...)} constructor form.
     *
     * <p>Handles three patterns:
     * <ol>
     *   <li>{@code pkg.ClassName#ClassName(params)} — plain class</li>
     *   <li>{@code pkg.Outer$Inner#Inner(params)} — inner class (Spoon uses
     *       the simple inner-class name, not the dollar-qualified one)</li>
     *   <li>{@code pkg.ClassName#<init>(params)} — already normalised; returned as-is</li>
     *   <li>{@code pkg.Type#fully.qualified.Type(params)} — legacy constructor form</li>
     * </ol>
     *
     * <p>Non-constructor IDs are returned unchanged.
     */
    public static String normalizeId(String id) {
        if (id == null) return "";
        int hash = id.indexOf('#');
        if (hash < 0) return id;

        String owner = id.substring(0, hash);
        String rest  = id.substring(hash + 1);

        // Already normalised
        if (rest.startsWith("<init>(")) return id;

        // Derive the simple class name that Spoon uses in the constructor signature.
        // For "pkg.Outer$Inner" the constructor is written "Inner(...)", not "Outer$Inner(...)".
        int dot    = owner.lastIndexOf('.');
        String dotPart = dot >= 0 ? owner.substring(dot + 1) : owner;  // e.g. "Outer$Inner"
        int dollar = dotPart.lastIndexOf('$');
        String simpleName = dollar >= 0 ? dotPart.substring(dollar + 1) : dotPart; // e.g. "Inner"

        if (rest.startsWith(simpleName + "(")) {
            return owner + "#<init>(" + rest.substring(simpleName.length() + 1);
        }

        // Legacy ids: Spoon sometimes emitted "owner#fully.qualified.Type(params)" for constructors.
        int openParen = rest.indexOf('(');
        if (openParen > 0 && rest.lastIndexOf(')') == rest.length() - 1) {
            String nameBeforeParams = rest.substring(0, openParen);
            if (nameBeforeParams.equals(simpleName)
                    || nameBeforeParams.endsWith("." + simpleName)) {
                return owner + "#<init>" + rest.substring(openParen);
            }
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
