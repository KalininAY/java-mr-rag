package com.example.mrrag.review.model;

import com.example.mrrag.graph.model.GraphNode;

/**
 * A contextual code snippet attached to a UnionLine to help the LLM understand impact.
 *
 * <p>{@link #lineContext()} indicates whether this snippet was collected for an ADD line,
 * a DELETE line, or both — so the LLM knows which side of the diff it relates to.
 *
 * <p>{@link #nodeId()} is the {@link GraphNode#id()} of the source node — use it to query
 * {@code GET /api/graph/node?nodeId=...} or {@code GET /api/graph/edges?nodeId=...}.
 * May be {@code null} for synthetic snippets not backed by a graph node.
 */
public record EnrichmentSnippet(
        SnippetType type,
        String nodeId,
        String filePath,
        int startLine,
        int endLine,
        String symbolName,
        String sourceSnippet,
        String explanation,
        LineContext lineContext
) {
    /** Which side of the diff triggered this snippet. */
    public enum LineContext {
        /** Snippet was collected for an ADD (+) line. */
        ADD,
        /** Snippet was collected for a DELETE (-) line. */
        DELETE,
        /** Snippet relates to both ADD and DELETE lines in the same union. */
        BOTH
    }

    // ------------------------------------------------------------------
    // Backward-compatible constructors (nodeId = null)
    // ------------------------------------------------------------------

    /** Backward-compat 7-arg constructor — defaults {@code lineContext} to {@link LineContext#BOTH}, nodeId to null. */
    public EnrichmentSnippet(SnippetType type, String filePath, int startLine, int endLine,
                             String symbolName, String sourceSnippet, String explanation) {
        this(type, null, filePath, startLine, endLine, symbolName, sourceSnippet, explanation, LineContext.BOTH);
    }

    /** Backward-compat 8-arg constructor (type, filePath, ..., lineContext) — nodeId = null. */
    public EnrichmentSnippet(SnippetType type, String filePath, int startLine, int endLine,
                             String symbolName, String sourceSnippet, String explanation,
                             LineContext lineContext) {
        this(type, null, filePath, startLine, endLine, symbolName, sourceSnippet, explanation, lineContext);
    }

    /** Full-body convenience constructor from {@link GraphNode} — uses {@code node.sourceSnippet()}, LineContext.BOTH. */
    public EnrichmentSnippet(SnippetType type, GraphNode node, String explanation) {
        this(type, node.id(), node.filePath(), node.startLine(), node.endLine(),
                node.simpleName(), node.sourceSnippet(), explanation, LineContext.BOTH);
    }

    // ------------------------------------------------------------------
    // Static factory methods
    // ------------------------------------------------------------------

    /**
     * Declaration-only factory — uses {@code node.declarationSnippet()} instead of the full body.
     * Suitable for {@link SnippetType#VARIABLE_DECLARATION} and {@link SnippetType#FIELD_DECLARATION}.
     */
    public static EnrichmentSnippet ofDeclaration(SnippetType type, GraphNode node,
                                                   String explanation, LineContext lineContext) {
        return new EnrichmentSnippet(
                type,
                node.id(),
                node.filePath(),
                node.startLine(),
                node.startLine(),
                node.simpleName(),
                node.declarationSnippet(),
                explanation,
                lineContext
        );
    }

    /** Backward-compat overload — defaults to LineContext.ADD. */
    public static EnrichmentSnippet ofDeclaration(SnippetType type, GraphNode node, String explanation) {
        return ofDeclaration(type, node, explanation, LineContext.ADD);
    }

    /**
     * Full-body factory — uses {@code node.sourceSnippet()} with explicit {@link LineContext}.
     */
    public static EnrichmentSnippet ofBody(SnippetType type, GraphNode node,
                                            String explanation, LineContext lineContext) {
        return new EnrichmentSnippet(
                type,
                node.id(),
                node.filePath(),
                node.startLine(),
                node.endLine(),
                node.simpleName(),
                node.sourceSnippet(),
                explanation,
                lineContext
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
        /** Declaration of a local variable */
        VARIABLE_DECLARATION,
        /** All usages of a local variable */
        VARIABLE_USAGES,
        /** Callee parameter names when arguments were changed */
        ARGUMENT_CONTEXT,
        /** Declaration of a class/interface (header + fields, without method bodies) */
        CLASS_DECLARATION,
        /** Full body of a class/interface/annotation (all members included) */
        CLASS_BODY
    }
}
