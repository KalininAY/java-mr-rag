package com.example.mrrag.review.model;

import java.util.List;

/**
 * Final representation of a single ChangeGroup — diff + collected context.
 * Fed directly to the LLM prompt builder.
 *
 * <p>Context snippets may overlap across groups (deduplication is intentional —
 * the same declaration may be relevant to multiple changes).
 */
public record GroupRepresentation(
        String groupId,
        ChangeType changeType,
        String primaryFile,
        List<ChangedLine> changedLines,
        /** Deduplicated context snippets collected for this group. */
        List<EnrichmentSnippet> contextSnippets,
        /** Human-readable markdown summary for the LLM prompt. */
        String markdown
) {}
