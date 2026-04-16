package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A cluster of related changed lines that should be reviewed together.
 *
 * <p>The {@link #flags} set carries optional diagnostic markers set by the
 * grouping pipeline (e.g. {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}).
 *
 * <p>{@link #intermediateNodes} carries AST nodes that lie on the path
 * between anchor nodes in the AST graph but are <em>not</em> themselves
 * changed. They are provided as read-only context so that
 * {@link com.example.mrrag.review.pipeline.GroupRepresentationBuilder}
 * can emit informational snippets (e.g. a method signature that
 * bridges two changed call sites). Empty for groups produced by the
 * legacy {@link com.example.mrrag.review.pipeline.ChangeGrouper}.
 */
public record ChangeGroup(
        String id,
        String primaryFile,
        List<ChangedLine> changedLines,
        List<EnrichmentSnippet> enrichments,
        Set<ChangeGroupFlag> flags,
        List<GraphNode> intermediateNodes
) {

    /** Canonical constructor — defensive copies to avoid aliasing. */
    public ChangeGroup {
        flags = flags == null || flags.isEmpty()
                ? EnumSet.noneOf(ChangeGroupFlag.class)
                : EnumSet.copyOf(flags);
        intermediateNodes = intermediateNodes == null
                ? List.of()
                : List.copyOf(intermediateNodes);
    }

    /**
     * Convenience factory without flags and without intermediate nodes
     * (backwards-compatible with existing call sites).
     */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                EnumSet.noneOf(ChangeGroupFlag.class), List.of());
    }

    /**
     * Convenience factory with flags but without intermediate nodes
     * (backwards-compatible with existing ChangeGrouper call sites).
     */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments,
                                  Set<ChangeGroupFlag> flags) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                flags, List.of());
    }

    /** Returns {@code true} when this group carries the given flag. */
    public boolean hasFlag(ChangeGroupFlag flag) {
        return flags.contains(flag);
    }

    /** Returns {@code true} when this group has AST intermediate nodes. */
    public boolean hasIntermediates() {
        return !intermediateNodes.isEmpty();
    }
}
