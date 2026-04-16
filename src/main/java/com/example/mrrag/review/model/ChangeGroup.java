package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A cluster of related changed lines that should be reviewed together.
 *
 * <p>{@link #flags} carries optional diagnostic markers set by the grouping
 * pipeline (e.g. {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}).
 *
 * <p>{@link #preMethodKey} is set by the legacy {@link com.example.mrrag.review.pipeline.ChangeGrouper}
 * (Phase 1) when Javadoc/annotation lines are attached to a method that immediately
 * follows them. Holds the {@code startLine} of that method as a String so that
 * {@link com.example.mrrag.review.pipeline.GroupRepresentationBuilder} can inject a
 * method-signature context block. {@code null} when not applicable.
 *
 * <p>{@link #intermediateNodes} is set by
 * {@link com.example.mrrag.review.pipeline.AstChangeGrouper} (Step 2/3) and holds
 * AST nodes that lie on the BFS path between the two anchor nodes but are not
 * themselves changed. They provide semantic context explaining the relationship.
 * Empty for groups produced by the legacy {@code ChangeGrouper}.
 */
public record ChangeGroup(
        String id,
        String primaryFile,
        List<ChangedLine> changedLines,
        List<EnrichmentSnippet> enrichments,
        Set<ChangeGroupFlag> flags,
        String preMethodKey,
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

    // ------------------------------------------------------------------
    // Convenience factories (backwards-compatible)
    // ------------------------------------------------------------------

    /** No flags, no preMethodKey, no intermediates. */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                EnumSet.noneOf(ChangeGroupFlag.class), null, List.of());
    }

    /** With flags, no preMethodKey, no intermediates. */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments,
                                  Set<ChangeGroupFlag> flags) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                flags, null, List.of());
    }

    /** With flags and preMethodKey (used by legacy ChangeGrouper). */
    public static ChangeGroup of(String id, String primaryFile,
                                  List<ChangedLine> changedLines,
                                  List<EnrichmentSnippet> enrichments,
                                  Set<ChangeGroupFlag> flags,
                                  String preMethodKey) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                flags, preMethodKey, List.of());
    }

    /** With intermediates (used by AstChangeGrouper). */
    public static ChangeGroup ofAst(String id, String primaryFile,
                                     List<ChangedLine> changedLines,
                                     List<EnrichmentSnippet> enrichments,
                                     List<GraphNode> intermediateNodes) {
        return new ChangeGroup(id, primaryFile, changedLines, enrichments,
                EnumSet.noneOf(ChangeGroupFlag.class), null, intermediateNodes);
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
