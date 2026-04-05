package com.example.mrrag.service;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Identifies a shard of a {@link AstGraphService.ProjectGraph}: project sources ({@link #MAIN})
 * or a dependency {@code *-sources.jar}.
 */
public final class GraphSegmentIds {

    public static final String MAIN = "main";

    private GraphSegmentIds() {
    }

    /**
     * Stable id for a sources jar: Maven coordinates when parsable, else {@code jar/<sha256(path)>}.
     */
    public static String segmentIdForJar(Path jarPath) {
        Optional<MavenArtifactCoordinates> gav = MavenArtifactCoordinates.tryParseJarPath(jarPath);
        if (gav.isPresent()) {
            MavenArtifactCoordinates g = gav.get();
            return "dep/" + g.groupId().replace('.', '/') + "/" + g.artifactId() + "/" + g.version();
        }
        return "jar/" + sha256Hex(jarPath.toAbsolutePath().normalize().toString());
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
