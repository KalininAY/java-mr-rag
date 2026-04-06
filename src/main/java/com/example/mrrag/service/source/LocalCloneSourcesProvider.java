package com.example.mrrag.service.source;

import com.example.mrrag.service.dto.ProjectSourceDto;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * {@link SourcesProvider} that reads {@code .java} files from a locally
 * cloned directory and populates {@link ProjectSourceDto#classpathRoot()}
 * so the graph builder can attempt compile-classpath resolution.
 *
 * <p>File collection is performed in parallel using
 * {@link Stream#parallel()} to maximise throughput on large repositories.
 * Results are accumulated in a thread-safe {@link ConcurrentLinkedQueue}.
 */
@Slf4j
public class LocalCloneSourcesProvider implements SourcesProvider {

    private final Path projectRoot;

    public LocalCloneSourcesProvider(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * Returns the absolute project root used by this provider.
     */
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public ProjectSourceDto getProjectSourceDto() throws Exception {
        List<ProjectSource> sources = collectSources();
        String id = "local:" + projectRoot;
        log.info("LocalCloneSourcesProvider: {} .java files from {}", sources.size(), projectRoot);
        return ProjectSourceDto.ofLocal(id, sources, projectRoot);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<ProjectSource> collectSources() throws IOException {
        if (!Files.isDirectory(projectRoot)) {
            log.warn("Project root does not exist or is not a directory: {}", projectRoot);
            return List.of();
        }

        ConcurrentLinkedQueue<ProjectSource> result = new ConcurrentLinkedQueue<>();

        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.parallel()
                .filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                .forEach(file -> {
                    String relPath = projectRoot.relativize(file)
                            .toString()
                            .replace('\\', '/');
                    try {
                        String content = Files.readString(file);
                        result.add(new ProjectSource(relPath, content));
                    } catch (IOException e) {
                        log.warn("Skipping unreadable file: {} — {}", relPath, e.getMessage());
                    }
                });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        return List.copyOf(result);
    }
}
