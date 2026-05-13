package com.example.mrrag.graph.model;

public enum EdgeKind {
    DECLARES, EXTENDS, IMPLEMENTS,
    INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS, REFERENCES_METHOD,
    READS_FIELD, WRITES_FIELD,
    READS_LOCAL_VAR, WRITES_LOCAL_VAR,
    THROWS,
    /** Element (method/class/field) → annotation type it is annotated with.
     *  Line range = the single line where the annotation is written.
     *  Allows finding annotation nodes for a given source line. */
    ANNOTATED_WITH,
    REFERENCES_TYPE, OVERRIDES,
    HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR,
    /** Compilation unit → import node (one edge per import statement). */
    HAS_IMPORT,
    IMPORTS,
    /** Type or member → its Javadoc node. */
    HAS_JAVADOC
}
