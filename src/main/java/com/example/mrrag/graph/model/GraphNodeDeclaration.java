package com.example.mrrag.graph.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

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
@AllArgsConstructor
@Getter
public class GraphNodeDeclaration implements GraphNode {
    private final String id;
    private final NodeKind kind;
    private final String simpleName;
    private final String filePath;
    private final int startLine;
    private final int endLine;
    private final String sourceSnippet;

}
