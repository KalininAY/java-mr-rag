package com.example.mrrag.graph.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Immutable node in the project symbol graph.
 *
 * <p>The {@link #bodyHash} field is computed eagerly at construction time
 * from {@code sourceSnippet} (falling back to {@code declarationSnippet},
 * then {@code id}) so that:
 * <ul>
 *   <li>Semantic diff comparisons are O(1) — no re-hashing on each call.</li>
 *   <li>The hash is persisted with the node when the graph is serialised to
 *       the disk cache ({@code ProjectGraphCacheStore}), avoiding any
 *       re-computation on cache load.</li>
 * </ul>
 *
 * <p>Whitespace is normalised before hashing so purely cosmetic re-formatting
 * (tab → space, extra blank lines) does not produce a different hash.
 */
public record GraphNode(
        String id,
        NodeKind kind,
        String simpleName,
        String filePath,
        int startLine,
        int endLine,
        String sourceSnippet,
        String declarationSnippet,
        String bodyHash) {

    /**
     * Canonical constructor — called by {@code GraphBuilderImpl} after the
     * source snippet has already been extracted from the line buffer.
     */
    public GraphNode {
        bodyHash = computeBodyHash(id, sourceSnippet, declarationSnippet);
    }

    /**
     * Convenience factory used by callers that do not yet have a
     * pre-computed hash (e.g. deserialisers, test builders).
     * The hash is recomputed from the supplied snippets.
     */
    public static GraphNode of(String id, NodeKind kind, String simpleName,
                               String filePath, int startLine, int endLine,
                               String sourceSnippet, String declarationSnippet) {
        return new GraphNode(id, kind, simpleName, filePath,
                startLine, endLine, sourceSnippet, declarationSnippet,
                /* bodyHash will be overwritten by compact constructor */ null);
    }

    // ------------------------------------------------------------------
    // Semantic comparison helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code other} has the same body hash,
     * i.e. the source bodies are identical modulo whitespace.
     */
    public boolean sameBodyAs(GraphNode other) {
        return other != null && this.bodyHash.equals(other.bodyHash);
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
