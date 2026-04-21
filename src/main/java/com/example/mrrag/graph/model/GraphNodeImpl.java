package com.example.mrrag.graph.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Immutable node in the project symbol graph
 */
@RequiredArgsConstructor
@Getter
public class GraphNodeImpl implements GraphNode, NodeWithDeclaration, NodeWithBody {
    private final String id;
    private final NodeKind kind;
    private final String simpleName;
    private final String filePath;
    private final int startLine;
    private final int endLine;
    private final String sourceSnippet;
//    private final String declarationSnippet;
    private final GraphNodeDeclaration declaration;


    // private GraphNodeDeclaration declarationCache;

    @Override
    public GraphNodeDeclaration declaration() {
        // if (declarationCache == null) {
        //     declarationCache = new GraphNodeDeclaration("declaration@" + id, kind, simpleName, filePath, startLine, , declarationSnippet);
        // }
        // return declarationCache;
        return declaration;
    }

    @Override
    public String getBody() {
        return sourceSnippet;
    }

    private String bodyHashCache;

    @Override
    public String getBodyHash() {
        if (bodyHashCache == null) {
            bodyHashCache = computeBodyHash(id, sourceSnippet, declaration.getSourceSnippet());
        }
        return bodyHashCache;
    }





    // ------------------------------------------------------------------
    // Internal hash computation
    // ------------------------------------------------------------------

    static String computeBodyHash(String id, String sourceSnippet, String declarationSnippet) {
        String body = sourceSnippet;
        if (body == null || body.isBlank()) body = declarationSnippet;
        if (body == null || body.isBlank()) body = id;
        String normalised = (body == null ? "" : body).strip().replaceAll("\\s+", " ");
        return sha256Hex(normalised);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
