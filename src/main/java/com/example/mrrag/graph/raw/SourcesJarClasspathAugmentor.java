package com.example.mrrag.graph.raw;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves {@code *-sources.jar} for compile classpath entries: sibling files on disk, then
 * Maven layout HTTP download using {@link ClasspathResolver.Result#remoteRepositories()}.
 */
@Slf4j
public final class SourcesJarClasspathAugmentor {

    private SourcesJarClasspathAugmentor() {
    }

    /**
     * Local sibling {@code *-sources.jar} only (no network).
     *
     * @deprecated use {@link #collectSourcesJars(ClasspathResolver.Result, Path, boolean)}
     */
    @Deprecated
    public static List<String> collectSourcesJars(String[] classpathEntries) {
        return collectSourcesJars(
                new ClasspathResolver.Result(classpathEntries, "", List.of()),
                Path.of(System.getProperty("java.io.tmpdir"), "mrrag-sources-unused"),
                false
        );
    }

    /**
     * @param resolution      classpath + repository URLs from {@link ClasspathResolver#tryResolve}
     * @param remoteCacheDir  directory for downloaded {@code -sources.jar} files
     * @param remoteEnabled   when false, only sibling {@code *-sources.jar} on disk are used
     */
    public static List<String> collectSourcesJars(
            ClasspathResolver.Result resolution,
            Path remoteCacheDir,
            boolean remoteEnabled) {
        String[] classpathEntries = resolution.entries();
        if (classpathEntries == null || classpathEntries.length == 0) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        List<String> repos = resolution.remoteRepositories();

        for (String p : classpathEntries) {
            if (p == null || p.isBlank()) {
                continue;
            }
            String trimmed = p.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) {
                continue;
            }
            if (lower.endsWith("-sources.jar") || lower.endsWith("-javadoc.jar")) {
                continue;
            }
            Path jarPath = Path.of(trimmed);
            if (!Files.isRegularFile(jarPath)) {
                continue;
            }

            String localSibling = toSourcesJarPath(trimmed);
            if (localSibling != null && Files.isRegularFile(Path.of(localSibling))) {
                if (seen.add(localSibling)) {
                    out.add(localSibling);
                }
                continue;
            }

            if (!remoteEnabled || repos.isEmpty()) {
                continue;
            }

            MavenArtifactCoordinates.tryParseJarPath(jarPath).flatMap(gav ->
                    RemoteSourcesJarResolver.download(gav, repos, remoteCacheDir)
            ).ifPresent(path -> {
                if (seen.add(path.toString())) {
                    out.add(path.toString());
                }
            });
        }

        int localSiblingCount = 0;
        Path cacheRoot = remoteCacheDir.toAbsolutePath().normalize();
        for (String s : out) {
            try {
                if (!Path.of(s).toAbsolutePath().normalize().startsWith(cacheRoot)) {
                    localSiblingCount++;
                }
            } catch (Exception ignored) {
                localSiblingCount++;
            }
        }
        if (!out.isEmpty()) {
            log.info("Resolved {} *-sources.jar for Spoon ({} sibling on disk, {} from repo download/cache)",
                    out.size(), localSiblingCount, out.size() - localSiblingCount);
        }
        return List.copyOf(out);
    }

    /**
     * {@code foo-1.0.jar} → {@code foo-1.0-sources.jar} in the same directory.
     */
    static String toSourcesJarPath(String binaryJarPath) {
        if (binaryJarPath.length() < 5 || !binaryJarPath.endsWith(".jar")) {
            return null;
        }
        String base = binaryJarPath.substring(0, binaryJarPath.length() - 4);
        return base + "-sources.jar";
    }
}
