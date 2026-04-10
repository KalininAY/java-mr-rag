package com.example.mrrag.review.model;

import java.util.List;

/**
 * Full enriched review context returned to the caller.
 */
public record ReviewContext(
        String repo,
        long mrIid,
        String sourceBranch,
        String targetBranch,
        String mrTitle,
        String mrDescription,
        List<ChangeGroup> groups,
        ReviewStats stats
) {}
