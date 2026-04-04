package com.example.mrrag.service.source;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link ProjectSourceProvider} that reads {@code .java} files from a
 * <strong>locally cloned</strong> repository directory.
 *
 * <p>The provider walks the directory tree with {@link Files#walk}, relativises
 * each path against the clone root, and returns the content as
 * {@link VirtualSource} records.  The actual Spoon model is still built in-memory
 * using {@link spoon.support.compiler.VirtualFile}, so the rest of the pipeline
 * is identical to the no-clone GitLab path.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProjectSourceProvider provider =
 *     new LocalCloneProjectSourceProvider(Path.of("/workspace/my-repo"));
 * List<VirtualSource> sources = provider.loadSources();
 * }</pre>
 */
@Slf4j
public class LocalCloneProjectSourceProvider implements ProjectSourceProvider {

    private final Path projectRoot;

    public LocalCloneProjectSourceProvider(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * Cache key: the normalised absolute path of the repository root.
     * Stable across calls as long as the clone directory does not change.
     */
    @Override
    public Object projectKey() {
        return projectRoot;
    }

    /**
     * Walks the clone directory recursively and loads every {@code .java} file.
     * Files that cannot be read are skipped with a {@code WARN} log entry.
     */
    @Override
    public List<VirtualSource> loadSources() throws IOException {
        log.info("[LocalCloneProjectSourceProvider] loading .java files from {}", projectRoot);

        List<VirtualSource> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        // Relativise so GraphNode.filePath() is consistent with diff paths
                        String rel = projectRoot.relativize(p).toString().replace("\\", "/");
                        result.add(new VirtualSource(rel, content));
                    } catch (IOException ex) {
                        log.warn("Skip {} — read error: {}", p, ex.getMessage());
                    }
                });
        }

        log.info("[LocalCloneProjectSourceProvider] loaded {} .java files from {}",
                result.size(), projectRoot);
        return result;
    }

    /**
     * Returns the absolute string path of the clone root so that the graph builder
     * can strip it when computing repo-relative paths.
     */
    @Override
    public String rootHint() {
        return projectRoot.toString();
    }
}
