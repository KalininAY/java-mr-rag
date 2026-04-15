package com.example.mrrag.review.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A cluster of related changed lines that should be reviewed together.
 *
 * <p>The {@link #flags} set carries optional diagnostic markers set by the
 * grouping pipeline (e.g. {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}).
 */
public record ChangeGroup(
        String id,
        String primaryFile,
        List<ChangedLine> changedLines,
        List<EnrichmentSnippet> enrichments,
        Set<ChangeGroupFlag> flags
) {

    /** Canonical constructor — defensive copy of flags to avoid aliasing. */
    public ChangeGroup {
        flags = flags == null || flags.isEmpty()
                ? EnumSet.noneOf(ChangeGroupFlag.class)
                : EnumSet.copyOf(flags);
    }

    /**
     * Convenience factory without flags (backwards-compatible with existing call sites).
     */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                EnumSet.noneOf(ChangeGroupFlag.class));
    }

    /** Returns {@code true} when this group carries the given flag. */
    public boolean hasFlag(ChangeGroupFlag flag) {
        return flags.contains(flag);
    }
}
