package com.example.mrrag.review.model;

/**
 * Semantic type of a ChangeGroup — derived from the mix of ChangedLine types
 * and the AST node kinds touched by the group.
 */
public enum ChangeType {
    /** Only ADD lines — new code introduced */
    ADDITION,
    /** Only DELETE lines — code removed */
    DELETION,
    /** Mix of ADD + DELETE in the same AST scope — in-place modification */
    MODIFICATION,
    /** Lines span multiple AST scopes (cross-file or multi-method) */
    CROSS_SCOPE
}
