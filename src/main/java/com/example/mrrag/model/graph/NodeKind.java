package com.example.mrrag.model.graph;

/**
 * Kinds of graph nodes.
 */
public enum NodeKind {
    /**
     * A concrete class, enum, or record type.
     */
    CLASS,
    /**
     * A Java {@code interface} declaration.
     */
    INTERFACE,
    /**
     * A constructor ({@code <init>}).
     */
    CONSTRUCTOR,
    /**
     * An instance or static method.
     */
    METHOD,
    /**
     * A class field.
     */
    FIELD,
    /**
     * A local variable or method parameter ({@code CtVariable} that is not a field).
     */
    VARIABLE,
    /**
     * A lambda expression.
     */
    LAMBDA,
    /**
     * An annotation type (target of {@code ANNOTATED_WITH} edges).
     */
    ANNOTATION,
    /**
     * A generic type parameter declaration.
     * Examples: {@code T} in {@code class Foo<T>},
     * {@code K extends Comparable<K>} in a method signature.
     */
    TYPE_PARAM,
    /**
     * An element declared inside an {@code @interface} body.
     * Example: {@code String value() default "";}
     */
    ANNOTATION_ATTRIBUTE
}
