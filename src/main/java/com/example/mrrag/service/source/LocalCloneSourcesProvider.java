package com.example.mrrag.service.source;

import com.example.mrrag.service.dto.ProjectSourceDto;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SourcesProvider} that reads {@code .java} files from a locally
 * cloned directory and populates {@link ProjectSourceDto#classpathRoot()}
 * so the graph builder can attempt compile-classpath resolution.
 *
 * <p>This replaces the previous {@link LocalCloneProjectSourceProvider}
 * as the recommended implementation for local projects.
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
        List<ProjectSource> result = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".java")) {
                    String relPath = projectRoot.relativize(file)
                            .toString()
                            .replace('\\', '/');
                    String content = Files.readString(file);
                    result.add(new ProjectSource(relPath, content));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("Skipping unreadable file: {} — {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
}
