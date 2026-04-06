package com.example.mrrag.service;

import com.example.mrrag.graph.raw.MavenArtifactCoordinates;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Identifies a shard of a {@link AstGraphService.ProjectGraph}: project sources ({@link #MAIN})
 * or a dependency {@code *-sources.jar}.
 *
 * <h3>Dep segment ID format</h3>
 * <ul>
 *   <li>Maven-resolvable jar: {@code dep/<groupId>_<artifactId>_<version>}<br>
 *       where {@code groupId} dots are kept as-is, e.g. {@code dep/com.example_foo_1.0.0}.
 *   <li>Unknown coordinates:  {@code jar/<sha256(absolutePath)>}
 * </ul>
 * The flat format (no nested dirs) maps 1:1 to a filesystem directory:
 * {@code depsDir/<segmentId>/graph.json}.
 */
public final class GraphSegmentIds {

    public static final String MAIN = "main";

    /** Prefix for Maven-resolvable dependency segments. */
    public static final String DEP_PREFIX = "dep/";

    /** Prefix for dependency segments with unknown Maven coordinates. */
    public static final String JAR_PREFIX = "jar/";

    private GraphSegmentIds() {
    }

    /**
     * Stable segment id for a sources jar.
     * <ul>
     *   <li>If Maven coordinates are parseable: {@code dep/<groupId>_<artifactId>_<version>}
     *   <li>Otherwise: {@code jar/<sha256(absolutePath)>}
     * </ul>
     */
    public static String segmentIdForJar(Path jarPath) {
        Optional<MavenArtifactCoordinates> gav = MavenArtifactCoordinates.tryParseJarPath(jarPath);
        if (gav.isPresent()) {
            MavenArtifactCoordinates g = gav.get();
            // Flat: dots in groupId are preserved, components separated by '_'
            return DEP_PREFIX + g.groupId() + "_" + g.artifactId() + "_" + g.version();
        }
        return JAR_PREFIX + sha256Hex(jarPath.toAbsolutePath().normalize().toString());
    }

    /** Returns true when the given segmentId represents a shared dependency shard. */
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
