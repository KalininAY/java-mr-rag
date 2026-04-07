package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.GraphBuilder.GraphNode;
import com.example.mrrag.graph.GraphBuilder.NodeKind;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract base for all typed linked (view) nodes in the symbol graph.
 *
 * <p>Every concrete subclass holds <em>direct Java references</em> to neighbouring
 * nodes, so callers can traverse the full graph without any ID look-ups.
 *
 * <p>Renamed from {@code GraphNodeView} (was in {@code com.example.mrrag.view}).
 */
public abstract class MarkdownNode {

    private final GraphNode node;
    private Map<String, List<Integer>> callerSiteLinesMap;
    private final List<MarkdownNode> declaredBy = new ArrayList<>();
    private final List<MarkdownClassNode> annotatedBy = new ArrayList<>();

    protected MarkdownNode(GraphNode node) {
        this.node = node;
    }

    // -- Identity --

    public String getId()           { return node.id(); }
    public NodeKind getKind()       { return node.kind(); }
    public String getSimpleName()   { return node.simpleName(); }
    public String getFilePath()     { return node.filePath(); }
    public int getStartLine()       { return node.startLine(); }
    public int getEndLine()         { return node.endLine(); }
    public String getContent()      { return node.sourceSnippet(); }
    public String getDeclaration()  { return node.declarationSnippet(); }
    public GraphNode getNode()      { return node; }

    // -- Structural links --

    public List<MarkdownNode>      getDeclaredBy()  { return declaredBy; }
    public List<MarkdownClassNode> getAnnotatedBy() { return annotatedBy; }

    public void addDeclaredBy(MarkdownNode owner)          { declaredBy.add(owner); }
    public void addAnnotatedBy(MarkdownClassNode annotation) { annotatedBy.add(annotation); }

    public void recordCallerInvocationSite(String callerId, int line) {
        if (callerId == null || line <= 0) return;
        if (callerSiteLinesMap == null) callerSiteLinesMap = new HashMap<>();
        callerSiteLinesMap.computeIfAbsent(callerId, k -> new ArrayList<>()).add(line);
    }

    protected List<Integer> callerSiteLinesFrom(String callerId) {
        if (callerSiteLinesMap == null) return List.of();
        return callerSiteLinesMap.getOrDefault(callerId, List.of());
    }

    protected List<MarkdownNode> outgoingCallees() { return List.of(); }

    protected List<Integer> edgeLinesTo(String targetId) { return List.of(); }

    // -- toString + toMarkdown (same logic as legacy GraphNodeView) --

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getId() + ")\n\n";
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Content\n");
        sb.append("### ").append(normalizeId(getId())).append('\n');
        appendNumberedSnippet(sb, getContent(), getStartLine());
        sb.append("\n# Context\n");
        for (Field field : collectFields(getClass())) {
            field.setAccessible(true);
            Object value;
            try { value = field.get(this); } catch (IllegalAccessException e) { value = "<inaccessible>"; }
            sb.append("## ").append(field.getName()).append('\n');
            if (value == null) {
                // skip
            } else if (value instanceof Collection<?> col) {
                appendGroupedCollection(sb, col, this, field.getName());
            } else if (value instanceof MarkdownNode view) {
                appendGroupedCollection(sb, List.of(view), this, field.getName());
            } else if (value instanceof Map) {
                // skip internal edge-line maps
            } else {
                sb.append("1|").append(value).append('\n');
            }
        }
        return sb.toString();
    }

    // -- Static helpers (mirrors GraphNodeView logic) --

    static String normalizeId(String id) {
        if (id == null) return "";
        return id.replace("#<init>", "#<init>");
    }

    private static void appendNumberedSnippet(StringBuilder sb, String snippet, int startLine) {
        if (startLine == -1) {
            if (snippet == null || snippet.isBlank()) { sb.append("-1|(external)\n"); return; }
            String[] lines = snippet.split("\n", -1);
            for (int i = 0; i < lines.length; i++) sb.append(-(i + 1)).append('|').append(lines[i]).append('\n');
            return;
        }
        if (snippet == null || snippet.isBlank()) { sb.append("0|(empty)\n"); return; }
        String[] lines = snippet.split("\n", -1);
        int first = (startLine > 0) ? startLine : 1;
        for (int i = 0; i < lines.length; i++) sb.append(first + i).append('|').append(lines[i]).append('\n');
    }

    private static void appendGroupedCollection(StringBuilder sb, Collection<?> col,
                                                MarkdownNode owner, String contextFieldName) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (Object element : col) {
            if (element instanceof MarkdownNode view) {
                MarkdownNode displayView = view;
                if ("instantiates".equals(contextFieldName) && view instanceof MarkdownClassNode cls) {
                    MarkdownNode ctor = resolveInstantiationConstructor(owner, cls);
                    if (ctor != null) displayView = ctor;
                }
                String id = normalizeId(displayView.getId());
                String key = "[" + displayView.node.kind() + "] " + id;
                sections.computeIfAbsent(key, k -> new ArrayList<>());
                List<String> bodyLines = sections.get(key);
                if (displayView.getStartLine() != -1 && bodyLines.isEmpty()) {
                    appendProjectElementLines(bodyLines, displayView, owner, contextFieldName);
                }
            } else {
                sections.computeIfAbsent("values", k -> new ArrayList<>()).add("1|" + element);
            }
        }
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
            for (String line : bodyLines) sb.append(line).append('\n');
        }
    }

    private static void appendProjectElementLines(List<String> bodyLines, MarkdownNode view,
                                                  MarkdownNode owner, String contextFieldName) {
        String decl = view.getDeclaration();
        String text = (decl != null && !decl.isBlank()) ? decl : view.getContent();
        if (text == null || text.isBlank()) { bodyLines.add(view.getStartLine() + "|(empty)"); return; }
        String[] lines = text.split("\n", -1);
        int first = view.getStartLine() > 0 ? view.getStartLine() : 1;
        for (int i = 0; i < lines.length; i++) bodyLines.add((first + i) + "|" + lines[i]);
    }

    private static boolean includeOutgoingLinesInHeader(String contextFieldName) {
        return switch (contextFieldName) {
            case "callees", "referencedMethods", "instantiates", "instantiatesAnon",
                 "readsFields", "writesFields", "throwsTypes", "referencesTypes" -> true;
            default -> false;
        };
    }

    private static String buildLinesAnnotation(Collection<?> col, String sectionKey,
                                               MarkdownNode owner, String contextFieldName) {
        int spaceIdx = sectionKey.indexOf("] ");
        if (spaceIdx < 0) return "";
        String normalizedId = sectionKey.substring(spaceIdx + 2);
        MarkdownNode targetView = null;
        for (Object element : col) {
            if (element instanceof MarkdownNode v) {
                MarkdownNode match = v;
                if ("instantiates".equals(contextFieldName) && v instanceof MarkdownClassNode cls) {
                    MarkdownNode ctor = resolveInstantiationConstructor(owner, cls);
                    if (ctor != null) match = ctor;
                }
                if (normalizeId(match.getId()).equals(normalizedId)) { targetView = match; break; }
            }
        }
        if (targetView == null) return "";
        List<Integer> lineNumbers = owner.edgeLinesTo(targetView.getId());
        if (lineNumbers.isEmpty()) return "";
        String joined = lineNumbers.stream().sorted().distinct().map(String::valueOf).collect(Collectors.joining(","));
        return " Lines:[" + joined + "]";
    }

    private static MarkdownNode resolveInstantiationConstructor(MarkdownNode owner, MarkdownClassNode cls) {
        List<Integer> classLines = owner.edgeLinesTo(cls.getId());
        if (classLines.isEmpty()) return null;
        Set<Integer> classLineSet = new HashSet<>(classLines);
        String classId = cls.getId();
        for (MarkdownNode c : owner.outgoingCallees()) {
            if (c.getKind() != NodeKind.CONSTRUCTOR) continue;
            String nid = normalizeId(c.getId());
            if (!nid.startsWith(classId + "#")) continue;
            for (int L : owner.edgeLinesTo(c.getId())) {
                if (classLineSet.contains(L)) return c;
            }
        }
        return null;
    }

    private static List<Field> collectFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != MarkdownNode.class; c = c.getSuperclass()) {
            fields.addAll(0, Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }
}
