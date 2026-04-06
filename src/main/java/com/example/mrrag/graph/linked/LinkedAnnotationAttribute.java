package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

/**
 * Linked node for an ANNOTATION_ATTRIBUTE element.
 * Renamed from {@code AnnotationAttributeView}.
 */
public class LinkedAnnotationAttribute extends LinkedNode {

    private String          defaultValue;
    private LinkedClassNode declaredByAnnotation;

    public LinkedAnnotationAttribute(GraphNode node) { super(node); }

    public String          getDefaultValue()         { return defaultValue; }
    public LinkedClassNode getDeclaredByAnnotation() { return declaredByAnnotation; }

    public void setDefaultValue(String v)            { this.defaultValue = v; }
    public void setDeclaredByAnnotation(LinkedClassNode v) { this.declaredByAnnotation = v; }
}
