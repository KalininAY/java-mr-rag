package com.example.mrrag.graph.model;

public enum NodeKind {
    CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
    LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE,
    /** A single {@code import} statement in a compilation unit. */
    IMPORT,
    /** A Javadoc comment attached to a type or member. */
    JAVADOC
}
