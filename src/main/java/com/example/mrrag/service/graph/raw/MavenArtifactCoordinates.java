package com.example.mrrag.service.graph.raw;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven GAV inferred from a {@code .jar} path in the local Maven repository, Gradle cache,
 * or a Maven-layout directory tree (including downloaded {@code *-sources.jar} under a project cache).
 */
public record MavenArtifactCoordinates(String groupId, String artifactId, String version) {

    private static final Set<String> MAVEN_GROUP_FIRST_SEGMENT = Set.of(
            "ai", "app", "at", "au", "biz", "br", "bz", "cc", "cloud", "cn", "co", "com", "de", "dev",
            "eu", "fr", "gradle", "info", "in", "io", "jakarta", "javax", "junit", "me", "maven", "name",
            "net", "nz", "org", "page", "pro", "sh", "software", "systems", "tech", "tools", "tv", "uk",
            "us", "work", "ws", "xyz");

    private static final Pattern GRADLE_FILES = Pattern.compile(
            "[/\\\\]files-2\\.1[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\]([^/\\\\]+)[/\\\\][^/\\\\]+[/\\\\]([^/\\\\]+\\.jar)$");

    public static Optional<MavenArtifactCoordinates> tryParseGradleCache(Path jarPath) {
        String p = jarPath.toString().replace('\\', '/');
        Matcher m = GRADLE_FILES.matcher(p);
        if (!m.find()) {
            return Optional.empty();
        }
        String groupId = m.group(1).replace('/', '.');
        String artifactId = m.group(2);
        String version = m.group(3);
        String fileName = m.group(4);
        if (!fileName.startsWith(artifactId + "-" + version)) {
            return Optional.empty();
        }
        return Optional.of(new MavenArtifactCoordinates(groupId, artifactId, version));
    }

    public static Optional<MavenArtifactCoordinates> tryParseMavenLocal(Path jarPath) {
        String p = jarPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        int repo = p.indexOf("/.m2/repository/");
        if (repo < 0) {
            return Optional.empty();
        }
        String tail = p.substring(repo + "/.m2/repository/".length());
        String[] parts = tail.split("/");
        if (parts.length < 4) {
            return Optional.empty();
        }
        String fileName = parts[parts.length - 1];
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return Optional.empty();
        }
        if (fileName.contains("-sources") || fileName.contains("-javadoc")) {
            return Optional.empty();
        }
        String version = parts[parts.length - 2];
        String artifactId = parts[parts.length - 3];
        StringBuilder gid = new StringBuilder();
        for (int i = 0; i < parts.length - 3; i++) {
            if (i > 0) gid.append('.');
            gid.append(parts[i]);
        }
        String expectedPrefix = artifactId + "-" + version;
        if (!fileName.startsWith(expectedPrefix + ".jar") && !fileName.startsWith(expectedPrefix + "-")) {
            return Optional.empty();
        }
        return Optional.of(new MavenArtifactCoordinates(gid.toString(), artifactId, version));
    }

    public static Optional<MavenArtifactCoordinates> tryParseMavenRepoLayout(Path jarPath) {
        String p = jarPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        String[] parts = p.split("/");
        int n = parts.length;
        if (n < 4) {
            return Optional.empty();
        }
        String fileName = parts[n - 1];
        String version = parts[n - 2];
        String artifactId = parts[n - 3];
        if (!fileName.equals(artifactId + "-" + version + "-sources.jar")
                && !fileName.equals(artifactId + "-" + version + ".jar")
                && !fileName.equals(artifactId + "-" + version + "-javadoc.jar")) {
            return Optional.empty();
        }
        int start = -1;
        for (int i = 0; i <= n - 4; i++) {
            if (MAVEN_GROUP_FIRST_SEGMENT.contains(parts[i])) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        String groupId = String.join(".", Arrays.copyOfRange(parts, start, n - 3));
        return Optional.of(new MavenArtifactCoordinates(groupId, artifactId, version));
    }

    public static Optional<MavenArtifactCoordinates> tryParseJarPath(Path jarPath) {
        Optional<MavenArtifactCoordinates> g = tryParseGradleCache(jarPath);
        if (g.isPresent()) return g;
        g = tryParseMavenLocal(jarPath);
        if (g.isPresent()) return g;
        return tryParseMavenRepoLayout(jarPath);
    }

    public String sourcesFileName() {
        return artifactId + "-" + version + "-sources.jar";
    }

    public String relativePath() {
        String g = groupId.replace('.', '/');
        return g + "/" + artifactId + "/" + version + "/" + sourcesFileName();
    }
}
