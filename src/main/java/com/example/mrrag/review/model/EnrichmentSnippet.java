package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

/**
 * A contextual code snippet attached to a ChangeGroup to help the LLM understand impact.
 *
 * <p>{@code nodeId} is the graph node identifier of the primary symbol represented by this snippet
 * (e.g. the caller method for METHOD_CALLERS, the field owner for FIELD_USAGES).
 * May be {@code null} for synthetic/aggregate snippets that have no single owning node.
 */
public record EnrichmentSnippet(
        SnippetType type,
        String nodeId,
        String filePath,
        int startLine,
        int endLine,
        String symbolName,
        String sourceSnippet,
        String explanation
) {
    // ------------------------------------------------------------------
    // Convenience constructors (backward-compat — nodeId defaults to null)
    // ------------------------------------------------------------------

    /** Full-body convenience constructor — uses {@code node.sourceSnippet()}. nodeId = node.id(). */
    public EnrichmentSnippet(SnippetType type, GraphNode node, String explanation) {
        this(type, node.id(), node.filePath(), node.startLine(), node.endLine(),
                node.simpleName(), node.sourceSnippet(), explanation);
    }

    /** Legacy 7-arg constructor without nodeId — sets nodeId to null. */
    public EnrichmentSnippet(SnippetType type,
                             String filePath, int startLine, int endLine,
                             String symbolName, String sourceSnippet, String explanation) {
        this(type, null, filePath, startLine, endLine, symbolName, sourceSnippet, explanation);
    }

    // ------------------------------------------------------------------
    // Factory methods
    // ------------------------------------------------------------------

    /**
     * Declaration-only factory — uses {@code node.declarationSnippet()} instead of the full body.
     * Suitable for {@link SnippetType#VARIABLE_DECLARATION} and {@link SnippetType#FIELD_DECLARATION}.
     * nodeId = node.id().
     */
    public static EnrichmentSnippet ofDeclaration(SnippetType type, GraphNode node, String explanation) {
        return new EnrichmentSnippet(
                type,
                node.id(),
                node.filePath(),
                node.startLine(),
                node.startLine(),
                node.simpleName(),
                node.declarationSnippet(),
                explanation
        );
    }

    /**
     * Factory for METHOD_CALLERS / FIELD_USAGES snippets where the owning node
     * is the caller/user method rather than the snippet's symbol.
     */
    public static EnrichmentSnippet ofCaller(SnippetType type, GraphNode callerNode,
                                             int startLine, int endLine,
                                             String symbolName, String sourceSnippet,
                                             String explanation) {
        return new EnrichmentSnippet(
                type,
                callerNode.id(),
                callerNode.filePath(),
                startLine,
                endLine,
                symbolName,
                sourceSnippet,
                explanation
        );
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
        ARGUMENT_CONTEXT,
        /** Full declaration of a class/interface */
        CLASS_DECLARATION
    }
}
