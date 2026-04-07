package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.GraphBuilder.GraphNode;

/**
 * Linked node for an ANNOTATION_ATTRIBUTE element.
 * Renamed from {@code AnnotationAttributeView}.
 */
public class MarkdownAnnotationAttribute extends MarkdownNode {

    private String          defaultValue;
    private MarkdownClassNode declaredByAnnotation;

    public MarkdownAnnotationAttribute(GraphNode node) { super(node); }

    public String          getDefaultValue()         { return defaultValue; }
    public MarkdownClassNode getDeclaredByAnnotation() { return declaredByAnnotation; }

    public void setDefaultValue(String v)            { this.defaultValue = v; }
    public void setDeclaredByAnnotation(MarkdownClassNode v) { this.declaredByAnnotation = v; }
}
