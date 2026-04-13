package com.example.mrrag.app.source;

import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * <p>Each returned {@link ProjectSource#path()} is relative to
 * {@code projectRoot} with forward slashes — matching paths GitLab reports in diff output.
 */
@Slf4j
@Setter
@NoArgsConstructor
public class LocalProjectSourceProvider implements ProjectSourceProvider {

    protected static final java.util.Set<String> EXCLUDED_DIRS = java.util.Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    protected Path projectRoot;

    public LocalProjectSourceProvider(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public Optional<Path> localProjectRoot() {
        return Optional.of(projectRoot);
    }

    @Override
    public List<ProjectSource> getSources() {
        log.info("Scanning local clone for .java files: {}", projectRoot);

        List<Path> sourceRoots = resolveSourceRoots();
        log.info("Source roots: {}", sourceRoots);

        List<ProjectSource> result = new ArrayList<>();
        String rootStr = projectRoot.toString();

        for (Path srcRoot : sourceRoots) {
            if (!Files.isDirectory(srcRoot)) continue;
            try (Stream<Path> walk = Files.walk(srcRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(absPath -> {
                            try {
                                String content = Files.readString(absPath, StandardCharsets.UTF_8);
                                String rel = relPath(rootStr, absPath.toAbsolutePath().toString());
                                result.add(new ProjectSource(rel, content));
                            } catch (IOException ex) {
                                log.warn("Skipping {} — read error: {}", absPath, ex.getMessage());
                            }
                        });
            } catch (IOException ex) {
                log.debug("Skip path source file, message ex = " + ex.getMessage());
            }
        }

        log.info("Loaded {} .java files from local clone {}", result.size(), projectRoot);
        return result;
    }

    protected List<Path> resolveSourceRoots() {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        );
        List<Path> standard = candidates.stream()
                .filter(Files::isDirectory)
                .toList();
        if (!standard.isEmpty()) return standard;

        List<Path> fallback = new ArrayList<>();
        try (Stream<Path> top = Files.list(projectRoot)) {
            top.filter(Files::isDirectory)
                    .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(fallback::add);
        } catch (IOException ex) {
            log.debug("Skip resolve source root, message ex = " + ex.getMessage());
        }
        return fallback.isEmpty() ? List.of(projectRoot) : fallback;
    }

    protected static String relPath(String root, String abs) {
        if (abs.startsWith(root)) {
            return abs.substring(root.length()).replaceFirst("^[/\\\\]", "").replace("\\", "/");
        }
        return abs.replace("\\", "/");
    }
}
