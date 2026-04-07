package com.example.mrrag.model.graph;

/**
 * Kinds of directed edges in the AST symbol graph.
 */
public enum EdgeKind {
    EXTENDS,
    IMPLEMENTS,
    CALLS,
    USES_FIELD,
    HAS_PARAM_TYPE,
    HAS_RETURN_TYPE,
    ANNOTATED_BY,
    HAS_MEMBER
}
