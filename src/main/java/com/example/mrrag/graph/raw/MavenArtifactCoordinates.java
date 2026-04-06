package com.example.mrrag.graph.raw;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven GAV coordinates parsed from a {@code *-sources.jar} path.
 */
public record MavenArtifactCoordinates(String groupId, String artifactId, String version) {

    /**
     * Pattern for local Maven repository layout:
     * {@code .m2/repository/<g/r/o/u/p>/<artifactId>/<version>/<artifactId>-<version>-sources.jar}
     */
    private static final Pattern M2_PATTERN = Pattern.compile(
            "\\.m2[/\\\\]repository[/\\\\](.+)[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\]\\2-\\3(?:-sources)?\\.jar$");

    /**
     * Pattern for Gradle cache layout:
     * {@code caches/modules-2/files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>-sources.jar}
     */
    private static final Pattern GRADLE_CACHE_PATTERN = Pattern.compile(
            "caches[/\\\\]modules-2[/\\\\]files-2\\.1[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\][^/\\\\]+[/\\\\]\\2-\\3(?:-sources)?\\.jar$");

    public static Optional<MavenArtifactCoordinates> tryParseJarPath(Path jarPath) {
        String s = jarPath.toString().replace('\\', '/');

        Matcher m2 = M2_PATTERN.matcher(s);
        if (m2.find()) {
            String group = m2.group(1).replace('/', '.');
            return Optional.of(new MavenArtifactCoordinates(group, m2.group(2), m2.group(3)));
        }

        Matcher gradle = GRADLE_CACHE_PATTERN.matcher(s);
        if (gradle.find()) {
            return Optional.of(new MavenArtifactCoordinates(gradle.group(1), gradle.group(2), gradle.group(3)));
        }

        return Optional.empty();
    }
}
