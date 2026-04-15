package com.example.mrrag.review.model;

/**
 * Diagnostic flags that can be attached to a {@link ChangeGroup} to signal
 * anomalies detected during grouping.
 */
public enum ChangeGroupFlag {

    /**
     * The group contains an import line that is not referenced by any changed
     * non-CONTEXT line in the same file.  This usually means the import belongs
     * to an unrelated change that sneaked into the diff.
     */
    SUSPICIOUS_UNUSED_IMPORT,

    /**
     * The group was produced by the rule-based version/changelog merger and
     * contains lines from both a build-descriptor file (e.g. {@code build.gradle})
     * and a changelog file (e.g. {@code CHANGELOG}).
     */
    VERSION_CHANGELOG_PAIR
}
