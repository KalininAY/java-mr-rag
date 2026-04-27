package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.markdown.MarkdownRenderUtils;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a {@link GroupRepresentation} for a single {@link UnionLine}.
 *
 * <p>Output structure per group:
 * <ol>
 *   <li>Header: union id + change type.</li>
 *   <li>Per-file {@code diff} block — ADD/DELETE lines with line numbers.</li>
 *   <li><b>Enclosing context</b> section — METHOD_BODY snippets.</li>
 *   <li><b>Context snippets</b> section — all other enrichment snippets,
 *       each labelled with {@code [ADD]} or {@code [DELETE]} from
 *       {@link EnrichmentSnippet#lineContext()}.</li>
 * </ol>
 */
@Component
public class GroupRepresentationBuilder {

    public GroupRepresentation build(
            UnionLine union,
            List<EnrichmentSnippet> contextSnippets
    ) {
        return build(union, contextSnippets, null);
    }

    public GroupRepresentation build(
            UnionLine union,
            List<EnrichmentSnippet> contextSnippets,
            ProjectGraph graph
    ) {
        ChangeType changeType = classifyUnion(union);
        String primaryFile = resolvePrimaryFile(union);
        String markdown = buildMarkdown(union, changeType, contextSnippets, graph);
        return new GroupRepresentation(
                union.id(),
                changeType,
                primaryFile,
                new ArrayList<>(union.changedLines()),
                contextSnippets,
                markdown
        );
    }

    // -----------------------------------------------------------------------
    // Classification
    // -----------------------------------------------------------------------

    static ChangeType classifyUnion(UnionLine union) {
        boolean hasAdd = false;
        boolean hasDel = false;
        Set<String> files = new HashSet<>();
        for (ChangedLine l : union.changedLines()) {
            if (l.type() == ChangedLine.LineType.ADD) hasAdd = true;
            if (l.type() == ChangedLine.LineType.DELETE) hasDel = true;
            files.add(l.filePath());
        }
        if (files.size() > 1) return ChangeType.CROSS_SCOPE;
        if (hasAdd && hasDel) return ChangeType.MODIFICATION;
        if (hasDel) return ChangeType.DELETION;
        return ChangeType.ADDITION;
    }

    static String resolvePrimaryFile(UnionLine union) {
        return union.changedLines().stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .collect(Collectors.groupingBy(ChangedLine::filePath, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(union.id());
    }

    // -----------------------------------------------------------------------
    // Markdown renderer
    // -----------------------------------------------------------------------

    private String buildMarkdown(
            UnionLine union,
            ChangeType changeType,
            List<EnrichmentSnippet> snippets,
            ProjectGraph graph
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Change group `").append(union.id())
                .append("` — ").append(changeType).append("\n");

        // --- 1. Per-file diff blocks ---
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : union.changedLines()) {
            byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<String, List<ChangedLine>> entry : byFile.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            sb.append("**File:** `").append(entry.getKey()).append("`\n\n");
            sb.append("```diff\n");
            for (ChangedLine l : entry.getValue()) {
                char prefix = l.type() == ChangedLine.LineType.ADD ? '+' : '-';
                int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
                String lineRef = lineNo > 0 ? String.format("@%-4d ", lineNo) : "       ";
                sb.append(prefix).append(lineRef).append(l.content()).append('\n');
            }
            sb.append("```\n\n");
        }

        // --- 2. AST graph nodes block ---
        renderGraphNodesBlock(sb, union);

        if (snippets == null || snippets.isEmpty()) return sb.toString();

        List<EnrichmentSnippet> methodBodies = snippets.stream()
                .filter(s -> s.type() == EnrichmentSnippet.SnippetType.METHOD_BODY)
                .collect(Collectors.toCollection(ArrayList::new));
        List<EnrichmentSnippet> otherSnippets = snippets.stream()
                .filter(s -> s.type() != EnrichmentSnippet.SnippetType.METHOD_BODY)
                .collect(Collectors.toCollection(ArrayList::new));

        // --- 3. Enclosing context (METHOD_BODY snippets) ---
        if (!methodBodies.isEmpty()) {
            sb.append("**Enclosing context (").append(methodBodies.size()).append("):**\n\n");
            for (EnrichmentSnippet s : methodBodies) {
                sb.append("`").append(s.symbolName()).append("`")
                        .append(" @ `").append(s.filePath())
                        .append(":").append(s.startLine()).append("`\n\n");
                String src = s.sourceSnippet();
                if (src != null && !src.isBlank()) {
                    sb.append("```\n");
                    MarkdownRenderUtils.appendNumberedSnippet(sb, src, s.startLine(), s.endLine());
                    sb.append("```\n\n");
                }
            }
        }

        // --- 4. Other context snippets ---
        if (!otherSnippets.isEmpty()) {
            sb.append("**Context snippets (").append(otherSnippets.size()).append("):**\n\n");
            for (EnrichmentSnippet s : otherSnippets) {
                String ctxLabel = lineContextLabel(s.lineContext());
                sb.append("- **").append(s.type()).append("** ")
                        .append(ctxLabel)
                        .append(" `").append(s.symbolName()).append("` @ `")
                        .append(s.filePath()).append(":")
                        .append(s.startLine()).append("`  \n");
                sb.append("  _").append(s.explanation()).append("_\n");
                String src = s.sourceSnippet();
                if (src != null && !src.isBlank()) {
                    sb.append("  ```\n");
                    String[] lines = src.split("\n", -1);
                    for (String line : MarkdownRenderUtils.numberedSnippetLines(
                            lines, s.startLine(), s.endLine(), true)) {
                        sb.append("  ").append(line).append('\n');
                    }
                    sb.append("  ```\n");
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    /** Returns a short bracketed label for the line context, e.g. {@code [ADD]}. */
    private static String lineContextLabel(EnrichmentSnippet.LineContext ctx) {
        if (ctx == null) return "";
        return switch (ctx) {
            case ADD    -> "[ADD] ";
            case DELETE -> "[DELETE] ";
            case BOTH   -> "[ADD/DELETE] ";
        };
    }

    /**
     * Appends a block listing all AST graph nodes associated with this union.
     */
    private void renderGraphNodesBlock(StringBuilder sb, UnionLine union) {
        if (union.graphNodes() == null || union.graphNodes().isEmpty()) return;
        sb.append("**AST nodes (").append(union.graphNodes().size()).append("):**\n\n");
        for (GraphNode n : union.graphNodes()) {
            sb.append("- `").append(n.id()).append("` (")
                    .append(n.kind()).append(") @ `")
                    .append(n.filePath()).append(":")
                    .append(n.startLine()).append("`\n");
        }
        sb.append('\n');
    }
}
