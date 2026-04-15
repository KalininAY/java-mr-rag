package com.example.mrrag.review.pipeline;

import com.example.mrrag.review.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a {@link GroupRepresentation} for a single ChangeGroup,
 * combining its diff lines with the context snippets collected by
 * the {@link ContextPipeline}.
 */
@Component
public class GroupRepresentationBuilder {

    /**
     * Assemble a representation from the group, its classified change type,
     * and the context snippets already collected by the pipeline's
     * {@link ContextCollector}.
     */
    public GroupRepresentation build(
            ChangeGroup group,
            ChangeType changeType,
            List<EnrichmentSnippet> contextSnippets
    ) {
        // Filter out snippets whose location is already covered by an ADD/DELETE
        // line inside the group diff (avoids duplicating content visible in the diff).
        Set<String> diffCoveredKeys = diffCoveredKeys(group);
        List<EnrichmentSnippet> dedupedSnippets = contextSnippets.stream()
                .filter(s -> !diffCoveredKeys.contains(snippetKey(s)))
                .collect(Collectors.toCollection(ArrayList::new));

        String markdown = buildMarkdown(group, changeType, dedupedSnippets);
        return new GroupRepresentation(
                group.id(),
                changeType,
                group.primaryFile(),
                group.changedLines(),
                dedupedSnippets,
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

        // Group changed lines by file to produce one diff block per file.
        // Use LinkedHashMap to preserve the order in which files first appear.
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : group.changedLines()) {
            byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        }

        for (Map.Entry<String, List<ChangedLine>> entry : byFile.entrySet()) {
            sb.append("**File:** `").append(entry.getKey()).append("`\n\n");
            sb.append("```diff\n");
            for (ChangedLine l : entry.getValue()) {
                char prefix = switch (l.type()) {
                    case ADD    -> '+';
                    case DELETE -> '-';
                    case CONTEXT -> ' ';
                };
                // Render line number: prefer new-side, fall back to old-side.
                int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
                String lineRef = lineNo > 0 ? String.format("@%-4d ", lineNo) : "       ";
                sb.append(prefix).append(lineRef).append(l.content()).append('\n');
            }
            sb.append("```\n\n");
        }

        if (snippets.isEmpty()) return sb.toString();

        // Context snippets
        sb.append("**Context snippets (").append(snippets.size()).append("):**\n\n");
        for (EnrichmentSnippet s : snippets) {
            sb.append("- **").append(s.type()).append("** `")
                    .append(s.symbolName()).append("` @ `")
                    .append(s.filePath()).append(":")
                    .append(s.startLine()).append("`  \n");
            sb.append("  _").append(s.explanation()).append("_\n");
            String src = s.sourceSnippet();
            if (src != null && !src.isBlank()) {
                sb.append("  ```java\n");
                for (String line : src.split("\n", -1)) {
                    sb.append("  ").append(line).append('\n');
                }
                sb.append("  ```\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Deduplication helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a set of {@code "filePath:line"} keys for every ADD/DELETE line in
     * the group.  Used to suppress context snippets that merely repeat content
     * already visible in the diff.
     */
    private static Set<String> diffCoveredKeys(ChangeGroup group) {
        Set<String> keys = new HashSet<>();
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
            if (lineNo > 0) keys.add(l.filePath() + ":" + lineNo);
        }
        return keys;
    }

    private static String snippetKey(EnrichmentSnippet s) {
        // A snippet is "covered" when its start line already appears as a diff line.
        return s.filePath() + ":" + s.startLine();
    }
}
