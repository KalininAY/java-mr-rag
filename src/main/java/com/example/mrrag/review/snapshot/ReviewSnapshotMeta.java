package com.example.mrrag.review.snapshot;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * Metadata saved alongside each review snapshot.
 * Serialised to {@code meta.json} inside the snapshot directory.
 *
 * <p>Fields {@code sourceCommitSha} and {@code targetCommitSha} hold the git HEAD SHA
 * of the respective branches at the moment the snapshot was created.
 * {@code targetCommitSha} is the primary key used by
 * {@link ReviewSnapshotReader#findMatchingSnapshot} to decide whether an existing
 * snapshot is still valid for a given MR.
 */
public record ReviewSnapshotMeta(
        String namespace,
        String repo,
        Long mrIid,
        String sourceBranch,
        String targetBranch,
        String mrTitle,
        /** HEAD SHA of the source branch at snapshot time (may be null if unavailable). */
        String sourceCommitSha,
        /** HEAD SHA of the target branch at snapshot time. Used for cache validation. */
        String targetCommitSha,
        /** Relative path to the cloned source project inside the snapshot dir. */
        String sourceRelDir,
        /** Relative path to the cloned target project inside the snapshot dir. */
        String targetRelDir,
        /** What data is currently present in this snapshot. Updated after each enrichment step. */
        SnapshotState state,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt
) {}
