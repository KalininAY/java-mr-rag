package com.example.mrrag.model.graph;

/**
 * Kinds of directed graph edges.
 * Each value can be toggled via {@code graph.edge.<name>.enabled=true/false}.
 */
public enum EdgeKind {

    // ── Structural (declaration) ────────────────────────────────────────────────

    /**
     * Owner declares a child member.
     * Examples: CLASS→METHOD, CLASS→FIELD, CLASS→CONSTRUCTOR, METHOD→LAMBDA.
     */
    DECLARES,

    // ── Type hierarchy ─────────────────────────────────────────────────────

    /**
     * {@code class A extends B} → A –EXTENDS→ B
     */
    EXTENDS,

    /**
     * {@code class A implements I} → A –IMPLEMENTS→ I
     */
    IMPLEMENTS,

    // ── Invocations ───────────────────────────────────────────────────────

    /**
     * {@code foo.bar()} → caller –INVOKES→ callee
     */
    INVOKES,

    /**
     * {@code new Foo(arg)} → caller –INSTANTIATES→ Foo
     */
    INSTANTIATES,

    /**
     * {@code new Runnable() { ... }} → caller –INSTANTIATES_ANONYMOUS→ anon-type
     */
    INSTANTIATES_ANONYMOUS,

    /**
     * {@code Foo::bar}, {@code Foo::new} → caller –REFERENCES_METHOD→ target
     */
    REFERENCES_METHOD,

    // ── Field access ────────────────────────────────────────────────────

    /**
     * {@code this.value}, {@code obj.field}
     */
    READS_FIELD,

    /**
     * {@code this.value = x}
     */
    WRITES_FIELD,

    // ── Local variable access ──────────────────────────────────────────────────

    /**
     * Caller reads a local variable or method parameter.
     */
    READS_LOCAL_VAR,

    /**
     * Caller writes a local variable or method parameter.
     */
    WRITES_LOCAL_VAR,

    // ── Exceptions ────────────────────────────────────────────────────────

    /**
     * Method/constructor contains {@code throw new FooException(...)}.
     */
    THROWS,

    // ── Annotations ──────────────────────────────────────────────────────

    /**
     * CLASS/INTERFACE/METHOD/FIELD → ANNOTATION type.
     */
    ANNOTATED_WITH,

    // ── Type references ─────────────────────────────────────────────────────

    /**
     * {@code Foo.class}, {@code instanceof Foo}, cast to {@code Foo}.
     */
    REFERENCES_TYPE,

    // ── Inheritance ──────────────────────────────────────────────────────

    /**
     * child –OVERRIDES→ parent method
     */
    OVERRIDES,

    // ── Generics ──────────────────────────────────────────────────────

    /**
     * A type or executable has a generic type parameter.
     * {@code class Foo<T>} → Foo –HAS_TYPE_PARAM→ T
     * {@code <R> R map(...)} → map –HAS_TYPE_PARAM→ R
     */
    HAS_TYPE_PARAM,

    /**
     * A type parameter has an upper bound.
     * {@code T extends Comparable<T>} → T –HAS_BOUND→ Comparable
     */
    HAS_BOUND,

    // ── Annotation attributes ───────────────────────────────────────────────────────

    /**
     * An annotation type declares an attribute element.
     * {@code @interface Foo { String value(); }} → Foo –ANNOTATION_ATTR→ value
     */
    ANNOTATION_ATTR
}
