package com.example.mrrag.review.model;

/**
 * Semantic label for a {@link com.example.mrrag.review.model.ChangeGroup}, computed in
 * {@link com.example.mrrag.review.pipeline.ContextPipeline#classifyGroup(com.example.mrrag.review.model.ChangeGroup)}.
 * <p>
 * It is <em>not</em> a 1:1 mapping from {@link com.example.mrrag.review.model.ChangedLine.LineType}:
 * {@code CONTEXT} lines do not set the add/delete flags; classification uses only
 * {@code ADD} / {@code DELETE} presence and, for {@link #CROSS_SCOPE}, distinct file paths
 * within the same merged group.
 */
public enum ChangeType {
    /** Group has ADD lines and no DELETE lines (CONTEXT ignored). */
    ADDITION,
    /** Group has DELETE lines and no ADD lines. */
    DELETION,
    /** Both ADD and DELETE lines appear in the group (same file after {@code classifyGroup} rules). */
    MODIFICATION,
    /**
     * More than one distinct {@link com.example.mrrag.review.model.ChangedLine#filePath()} in the group
     * (e.g. after cross-file merge in {@link com.example.mrrag.review.pipeline.ChangeGrouper}).
     */
    CROSS_SCOPE
}
