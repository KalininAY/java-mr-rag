package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.markdown.MarkdownRenderUtils;
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
 *   <li>Per-file {@code diff} block — all lines received (ADD/DELETE only,
 *       since {@link DiffParser} no longer emits CONTEXT lines).
 *       Each line is prefixed with its line number ({@code @N}).</li>
 *   <li><b>Enclosing context</b> section — one block per
 *       {@link EnrichmentSnippet.SnippetType#METHOD_BODY} snippet.
 *       Lines are numbered in {@code lineNo| text} format via
 *       {@link MarkdownRenderUtils#appendNumberedSnippet}.</li>
 *   <li><b>Context snippets</b> section — all other enrichment snippets,
 *       also with numbered lines.</li>
 * </ol>
 *
 * <p>Deduplication of snippets already in the diff is performed upstream by
 * {@link com.example.mrrag.review.strategy.ContextStrategy#filterAlreadyInDiff}.
 */
@Component
public class GroupRepresentationBuilder {

    public GroupRepresentation build(
            ChangeGroup group,
            ChangeType changeType,
            List<EnrichmentSnippet> contextSnippets
    ) {
        String markdown = buildMarkdown(group, changeType, contextSnippets);
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
            List<EnrichmentSnippet> snippets
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

        if (snippets.isEmpty()) return sb.toString();

        // Split into METHOD_BODY vs. everything else
        List<EnrichmentSnippet> methodBodies = snippets.stream()
                .filter(s -> s.type() == EnrichmentSnippet.SnippetType.METHOD_BODY)
                .collect(Collectors.toCollection(ArrayList::new));
        List<EnrichmentSnippet> otherSnippets = snippets.stream()
                .filter(s -> s.type() != EnrichmentSnippet.SnippetType.METHOD_BODY)
                .collect(Collectors.toCollection(ArrayList::new));

        // --- 2. Enclosing context (METHOD_BODY snippets) ---
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

        // --- 3. Other context snippets ---
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
}
