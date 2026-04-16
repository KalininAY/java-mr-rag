package com.example.mrrag.graph.model;

public enum EdgeKind {
    DECLARES, EXTENDS, IMPLEMENTS,
    INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS, REFERENCES_METHOD,
    READS_FIELD, WRITES_FIELD,
    READS_LOCAL_VAR, WRITES_LOCAL_VAR,
    THROWS, ANNOTATED_WITH, REFERENCES_TYPE, OVERRIDES,
    HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR,
    /** Compilation unit → import node (one edge per import statement). */
    HAS_IMPORT,
    /** Type or member → its Javadoc node. */
    HAS_JAVADOC
}
