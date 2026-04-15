package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.markdown.MarkdownRenderUtils;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a {@link GroupRepresentation} for a single ChangeGroup.
 *
 * <p>Output structure per group:
 * <ol>
 *   <li>Header: group id + change type.</li>
 *   <li>Per-file {@code diff} block — all lines received (ADD/DELETE only).
 *       Each line is prefixed with its line number ({@code @N}).</li>
 *   <li><b>Method signature</b> section (optional) — shown when the group
 *       contains Javadoc or annotation lines that were attached to a method
 *       in Phase 1 (i.e. {@code preMethodKey} is set in group metadata).
 *       Renders the method signature line(s) from the graph so the reviewer
 *       can immediately see which element was annotated/documented.</li>
 *   <li><b>Enclosing context</b> section — one block per
 *       {@link EnrichmentSnippet.SnippetType#METHOD_BODY} snippet.
 *       Lines are numbered in {@code lineNo| text} format.</li>
 *   <li><b>Context snippets</b> section — all other enrichment snippets,
 *       also with numbered lines.</li>
 * </ol>
 */
@Component
public class GroupRepresentationBuilder {

    /**
     * Build a representation without graph access (no method-signature injection).
     * Used by tests and callers that don't hold a graph reference.
     */
    public GroupRepresentation build(
            ChangeGroup group,
            ChangeType changeType,
            List<EnrichmentSnippet> contextSnippets
    ) {
        return build(group, changeType, contextSnippets, null);
    }

    /**
     * Build a representation, optionally injecting method-signature context
     * for groups whose Javadoc/annotations were attached to a method in Phase 1.
     *
     * @param graph source-branch AST graph; may be {@code null}
     */
    public GroupRepresentation build(
            ChangeGroup group,
            ChangeType changeType,
            List<EnrichmentSnippet> contextSnippets,
            ProjectGraph graph
    ) {
        String markdown = buildMarkdown(group, changeType, contextSnippets, graph);
        return new GroupRepresentation(
                group.id(),
                changeType,
                group.primaryFile(),
                group.changedLines(),
                contextSnippets,
                markdown
        );
    }

    // -----------------------------------------------------------------------
    // Markdown renderer
    // -----------------------------------------------------------------------

    private String buildMarkdown(
            ChangeGroup group,
            ChangeType changeType,
            List<EnrichmentSnippet> snippets,
            ProjectGraph graph
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Change group `").append(group.id())
                .append("` — ").append(changeType).append("\n");

        // --- 1. Per-file diff blocks ---
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : group.changedLines()) {
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

        // --- 2. Method signature block (for Javadoc/annotation groups) ---
        // Rendered when preMethodKey metadata is set and a graph is available.
        // Shows the declaration line of the method the Javadoc/annotations belong to,
        // giving reviewers immediate context without opening the file.
        renderMethodSignatureBlock(sb, group, graph);

        if (snippets.isEmpty()) return sb.toString();

        // Split into METHOD_BODY vs. everything else
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
                sb.append("- **").append(s.type()).append("** `")
                        .append(s.symbolName()).append("` @ `")
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

    /**
     * Appends a "Method signature" block when the group carries a
     * {@code preMethodKey} metadata entry and the graph is non-null.
     *
     * <p>The block looks like:
     * <pre>
     * **Method signature:**
     *
     * `cleanAttachments` @ `...JiraParameterizedTest.java:74`
     *
     * ```java
     * 74|    boolean cleanAttachments() default false;
     * ```
     * </pre>
     *
     * For annotation groups the method may span several lines (full signature
     * with parameter list), so we render from {@code startLine} to the first
     * line that ends with {@code {}, {@code ;}, or the end of the node,
     * whichever comes first — capped at 5 lines to avoid dumping the whole body.
     */
    private void renderMethodSignatureBlock(StringBuilder sb, ChangeGroup group, ProjectGraph graph) {
        if (graph == null) return;
        String rawKey = group.metadata().get("preMethodKey");
        if (rawKey == null || rawKey.isBlank()) return;

        int methodStartLine;
        try {
            methodStartLine = Integer.parseInt(rawKey.trim());
        } catch (NumberFormatException e) {
            return;
        }

        // Find the method node in any file of the group that matches this startLine
        GraphNode methodNode = null;
        Set<String> groupFiles = new LinkedHashSet<>();
        for (ChangedLine l : group.changedLines()) groupFiles.add(l.filePath());

        outer:
        for (String file : groupFiles) {
            for (GraphNode n : graph.nodes.values()) {
                if (n.kind() == NodeKind.METHOD
                        && file.equals(n.filePath())
                        && n.startLine() == methodStartLine) {
                    methodNode = n;
                    break outer;
                }
            }
        }
        if (methodNode == null) return;

        sb.append("**Method signature:**\n\n");
        sb.append("`").append(simpleMethodName(methodNode.id())).append("`")
                .append(" @ `").append(methodNode.filePath())
                .append(":").append(methodNode.startLine()).append("`\n\n");

        String src = methodNode.sourceSnippet();
        if (src != null && !src.isBlank()) {
            // Render only the signature portion: up to the first '{' or ';' line, max 5 lines
            String[] lines = src.split("\n", -1);
            int sigLines = 0;
            StringBuilder sigSrc = new StringBuilder();
            for (String line : lines) {
                sigSrc.append(line).append('\n');
                sigLines++;
                String trimmed = line.stripTrailing();
                if (trimmed.endsWith("{") || trimmed.endsWith(";") || sigLines >= 5) break;
            }
            sb.append("```java\n");
            MarkdownRenderUtils.appendNumberedSnippet(
                    sb, sigSrc.toString(), methodNode.startLine(),
                    methodNode.startLine() + sigLines - 1);
            sb.append("```\n\n");
        }
    }

    private static String simpleMethodName(String qualifiedId) {
        if (qualifiedId == null) return "";
        int hash = qualifiedId.lastIndexOf('#');
        String name = hash >= 0 ? qualifiedId.substring(hash + 1) : qualifiedId;
        int paren = name.indexOf('(');
        return paren >= 0 ? name.substring(0, paren) : name;
    }
}
