package com.example.mrrag.service.graph.raw;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Identifies a shard of a project graph: project sources ({@link #MAIN})
 * or a dependency {@code *-sources.jar}.
 */
public final class GraphSegmentIds {

    public static final String MAIN = "main";
    public static final String DEP_PREFIX = "dep/";
    public static final String JAR_PREFIX = "jar/";

    private GraphSegmentIds() {
    }

    public static String segmentIdForJar(Path jarPath) {
        Optional<MavenArtifactCoordinates> gav = MavenArtifactCoordinates.tryParseJarPath(jarPath);
        if (gav.isPresent()) {
            MavenArtifactCoordinates g = gav.get();
            return DEP_PREFIX + g.groupId() + "_" + g.artifactId() + "_" + g.version();
        }
        return JAR_PREFIX + sha256Hex(jarPath.toAbsolutePath().normalize().toString());
    }

    public static boolean isDepSegment(String segmentId) {
        return segmentId != null &&
                (segmentId.startsWith(DEP_PREFIX) || segmentId.startsWith(JAR_PREFIX));
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
