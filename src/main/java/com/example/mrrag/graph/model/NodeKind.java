package com.example.mrrag.graph.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum NodeKind {
    CLASS(true),
    INTERFACE(true),
    ANNOTATION(true),
    CONSTRUCTOR(true),
    METHOD(true),
    INIT_BLOCK(true),
    LAMBDA(true),
    FIELD(false),
    VARIABLE(false),
    TYPE_PARAM(false),
    ANNOTATION_ATTRIBUTE(false),
    IMPORT(false),
    JAVADOC(false);

    private final boolean hasBody;
}
