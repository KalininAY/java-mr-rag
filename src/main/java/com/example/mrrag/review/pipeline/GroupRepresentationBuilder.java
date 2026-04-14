package com.example.mrrag.review.pipeline;

import com.example.mrrag.review.model.*;
import org.springframework.stereotype.Component;

import java.util.List;

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
        sb.append("**File:** `").append(group.primaryFile()).append("`\n\n");

        // Diff block
        sb.append("```diff\n");
        for (ChangedLine l : group.changedLines()) {
            char prefix = switch (l.type()) {
                case ADD -> '+';
                case DELETE -> '-';
                case CONTEXT -> ' ';
            };
            sb.append(prefix).append(l.content()).append('\n');
        }
        sb.append("```\n\n");

        if (snippets.isEmpty())
            return sb.toString();

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
}
