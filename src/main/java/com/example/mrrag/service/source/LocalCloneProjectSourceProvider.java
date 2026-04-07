package com.example.mrrag.service.source;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * {@link ProjectSourceProvider} that reads {@code .java} files from a
 * <strong>locally cloned</strong> repository directory.
 *
 * <p>Source-root resolution follows the standard Maven / Gradle layout:
 * <ol>
 *   <li>{@code <root>/src/main/java}</li>
 *   <li>{@code <root>/src/test/java}</li>
 *   <li>Fallback: all non-hidden, non-build top-level subdirectories.</li>
 *   <li>Last resort: {@code <root>} itself.</li>
 * </ol>
 *
 * <p>File collection is performed in parallel using {@link Stream#parallel()}
 * to maximise throughput on large repositories.
 *
 * <p>Each returned {@link ProjectSource#path()} is relative to
 * {@code projectRoot} with forward slashes — matching paths GitLab reports
 * in diff output.
 */
@Slf4j
public class LocalCloneProjectSourceProvider implements ProjectSourceProvider {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    private final Path projectRoot;

    public LocalCloneProjectSourceProvider(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    /**
     * Returns a stable cache key in the format {@code "local:<absolutePath>"}.
     * Example: {@code "local:/tmp/repo-clone"}.
     */
    @Override
    public String projectId() {
        return "local:" + projectRoot;
    }

    @Override
    public Optional<Path> localProjectRoot() {
        return Optional.of(projectRoot);
    }

    @Override
    public List<ProjectSource> getSources() throws IOException {
        log.info("Scanning local clone for .java files: {}", projectRoot);

        if (!Files.isDirectory(projectRoot)) {
            log.warn("Project root does not exist or is not a directory: {}", projectRoot);
            return List.of();
        }

        List<Path> sourceRoots = resolveSourceRoots();
        log.info("Source roots: {}", sourceRoots);

        ConcurrentLinkedQueue<ProjectSource> result = new ConcurrentLinkedQueue<>();

        for (Path srcRoot : sourceRoots) {
            if (!Files.isDirectory(srcRoot)) continue;
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                walk.parallel()
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(absPath -> {
                        String rel = projectRoot.relativize(absPath)
                                .toString()
                                .replace('\\', '/');
                        try {
                            String content = Files.readString(absPath, StandardCharsets.UTF_8);
                            result.add(new ProjectSource(rel, content));
                        } catch (IOException ex) {
                            log.warn("Skipping {} — read error: {}", rel, ex.getMessage());
                        }
                    });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }

        List<ProjectSource> sources = List.copyOf(result);
        log.info("Loaded {} .java files from local clone {}", sources.size(), projectRoot);
        return sources;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<Path> resolveSourceRoots() throws IOException {
        List<Path> standard = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        ).stream().filter(Files::isDirectory).toList();

        if (!standard.isEmpty()) return standard;

        // Fallback: non-hidden, non-build top-level subdirectories
        try (Stream<Path> top = Files.list(projectRoot)) {
            List<Path> fallback = top
                    .filter(Files::isDirectory)
                    .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();
            return fallback.isEmpty() ? List.of(projectRoot) : fallback;
        }
    }
}
