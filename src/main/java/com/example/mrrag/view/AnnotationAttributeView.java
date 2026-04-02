package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#ANNOTATION_ATTRIBUTE} node.
 *
 * <p>Represents a single element declared inside an {@code @interface} body:
 * <pre>{@code
 * public @interface Retry {
 *     int times() default 3;          // ← ANNOTATION_ATTRIBUTE node
 *     Class<? extends Throwable>[] on() default {};
 * }
 * }</pre>
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed method
 * declaration including the default value clause (if any), so it is
 * ready to embed directly in an LLM prompt.
 */
public class AnnotationAttributeView extends GraphNodeView {

    /**
     * String representation of the default value as it appears in source,
     * e.g. {@code "3"}, {@code "\"\""}, {@code "{}"}, or {@code null} if
     * no default is declared.
     */
    private String defaultValue;

    /** The annotation type that declares this attribute. */
    private ClassNodeView declaredByAnnotation;

    AnnotationAttributeView(GraphNode node) {
        super(node);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Source-level default value string, or {@code null} if absent.
     * Examples: {@code "3"}, {@code "true"}, {@code "RetentionPolicy.RUNTIME"}.
     */
    public String getDefaultValue() { return defaultValue; }

    /**
     * The {@code @interface} type that owns this attribute.
     * Never {@code null} after the graph is wired.
     */
    public ClassNodeView getDeclaredByAnnotation() { return declaredByAnnotation; }

    // ── Package-private mutators used by GraphViewBuilder ─────────────────────

    void setDefaultValue(String defaultValue)           { this.defaultValue = defaultValue; }
    void setDeclaredByAnnotation(ClassNodeView ann)     { this.declaredByAnnotation = ann; }
}
