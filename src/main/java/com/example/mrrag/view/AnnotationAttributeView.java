package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#ANNOTATION_ATTRIBUTE}
 * node.
 *
 * <p>Represents a single element declared inside an {@code @interface} body.
 * In Java source such elements look like abstract method declarations, and
 * Spoon models them as {@code CtAnnotationMethod} instances.
 *
 * <p>Example annotation type:
 * <pre>{@code
 * public @interface Retry {
 *     int     times()  default 3;        // ANNOTATION_ATTRIBUTE
 *     boolean logged() default false;   // ANNOTATION_ATTRIBUTE
 *     Class<? extends Throwable>[] on() default {};
 * }
 * }</pre>
 *
 * <p>Node id format: {@code AnnotationType#attrName}, e.g.
 * {@code com.example.Retry#times}.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed method
 * declaration including the {@code default} clause, e.g.
 * {@code "int times() default 3;"}. This makes it ready to embed in
 * an LLM prompt without additional file I/O.
 */
public class AnnotationAttributeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Attribute properties
    // -------------------------------------------------------------------------

    /**
     * String representation of the default value as it appears in source,
     * or {@code null} if no default value is declared.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code int times() default 3} → {@code "3"}</li>
     *   <li>{@code boolean logged() default false} → {@code "false"}</li>
     *   <li>{@code String prefix() default ""} → {@code "\"\""}</li>
     *   <li>{@code Class[] on() default {}} → {@code "{}"}</li>
     *   <li>{@code String value()} (no default) → {@code null}</li>
     * </ul>
     */
    private String defaultValue;

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The annotation type ({@code @interface}) that declares this attribute.
     *
     * <p>Populated from the reverse of the {@code ANNOTATION_ATTR} edge
     * ({@code AnnotationType → attribute}).
     *
     * <p>Never {@code null} after the graph is wired.
     */
    private ClassNodeView declaredByAnnotation;

    AnnotationAttributeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the source-level default value string, or {@code null} if
     * no default is declared.
     *
     * <p>Examples: {@code "3"}, {@code "true"},
     * {@code "RetentionPolicy.RUNTIME"}, {@code "{}"}.
     *
     * @return default value string, or {@code null}
     */
    public String getDefaultValue() { return defaultValue; }

    /**
     * Returns the {@code @interface} type that owns this attribute.
     *
     * @return annotation type view; never {@code null} after wiring
     */
    public ClassNodeView getDeclaredByAnnotation() { return declaredByAnnotation; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    /** Sets the source-level default value string (may be {@code null}). */
    void setDefaultValue(String defaultValue)        { this.defaultValue = defaultValue; }

    /** Sets the annotation type that owns this attribute. */
    void setDeclaredByAnnotation(ClassNodeView ann)  { this.declaredByAnnotation = ann; }
}
