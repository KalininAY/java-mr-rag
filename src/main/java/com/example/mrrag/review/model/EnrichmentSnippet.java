package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

/**
 * A contextual code snippet attached to a ChangeGroup to help the LLM understand impact.
 */
public record EnrichmentSnippet(
        SnippetType type,
        String filePath,
        int startLine,
        int endLine,
        String symbolName,
        String sourceSnippet,
        String explanation
) {
    public EnrichmentSnippet(SnippetType type, GraphNode node, String explanation) {
        this(type, node.filePath(), node.startLine(), node.endLine(), node.simpleName(), node.sourceSnippet(), explanation);
    }

    public enum SnippetType {
        /** Full declaration of a method (signature + javadoc) */
        METHOD_DECLARATION,
        /** Full (or truncated) body of a method */
        METHOD_BODY,
        /** List of sites that call a changed/used method */
        METHOD_CALLERS,
        /** Full declaration line of a field */
        FIELD_DECLARATION,
        /** All access sites of a field (reads + writes) */
        FIELD_USAGES,
        /** Declaration line of a local variable */
        VARIABLE_DECLARATION,
        /** All usages of a local variable */
        VARIABLE_USAGES,
        /** Callee parameter names when arguments were changed */
        ARGUMENT_CONTEXT
    }
}
