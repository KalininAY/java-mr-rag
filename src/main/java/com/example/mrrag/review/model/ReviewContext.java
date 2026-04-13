package com.example.mrrag.review.model;

import com.example.mrrag.review.pipeline.GroupRepresentation;

import java.util.List;

/**
 * Full enriched review context returned to the caller.
 *
 * <p>Adds {@code representations} — one {@link GroupRepresentation} per
 * change group, produced by the RAG pipeline. Each representation bundles
 * the diff lines, the change type, and the collected context snippets
 * that will be fed to the LLM prompt builder.
 */
public record ReviewContext(
        String repo,
        long mrIid,
        String sourceBranch,
        String targetBranch,
        String mrTitle,
        String mrDescription,
        List<ChangeGroup> groups,
        /** RAG pipeline output — one per group, in same order as {@code groups}. */
        List<GroupRepresentation> representations,
        ReviewStats stats
) {}
