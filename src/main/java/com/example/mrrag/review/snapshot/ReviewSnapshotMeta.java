package com.example.mrrag.review.snapshot;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Metadata saved alongside each review snapshot.
 * Serialised to {@code meta.json} inside the snapshot directory.
 */
public record ReviewSnapshotMeta(
        String namespace,
        String repo,
        Long mrIid,
        String sourceBranch,
        String targetBranch,
        String mrTitle,
        /** Relative path to the cloned source project inside the snapshot dir */
        String sourceRelDir,
        /** Relative path to the cloned target project inside the snapshot dir */
        String targetRelDir,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt
) {}
