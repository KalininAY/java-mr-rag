package com.example.mrrag.app.source;

import com.example.mrrag.commons.source.JavaSourceLoader;
import com.example.mrrag.commons.source.VirtualSource;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link JavaSourceLoader} implementation that reads {@code .java} files
 * from a <strong>locally cloned</strong> repository directory.
 *
 * <p>Instantiate per-request with the clone root:
 * <pre>{@code
 *   JavaSourceLoader loader = new LocalFileSourceLoader(cloneRoot);
 *   List<VirtualSource> sources = loader.loadSources();
 * }</pre>
 */
@Slf4j
public class LocalFileSourceLoader implements JavaSourceLoader {

    private final Path root;

    public LocalFileSourceLoader(Path root) {
        this.root = root;
    }

    @Override
    public List<VirtualSource> loadSources() throws IOException {
        log.info("Scanning local directory for .java files: {}", root);
        List<VirtualSource> result = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> javaPaths = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            log.info("Found {} .java files under {}", javaPaths.size(), root);

            for (Path absPath : javaPaths) {
                try {
                    String content = Files.readString(absPath, StandardCharsets.UTF_8);
                    // repo-relative path so GraphNode.filePath() is consistent with diff paths
                    String relPath  = root.relativize(absPath).toString().replace("\\", "/");
                    result.add(new VirtualSource(relPath, content));
                } catch (IOException ex) {
                    log.warn("Skipping file {} — read error: {}", absPath, ex.getMessage());
                }
            }
        }

        log.info("Loaded {}/{} .java files from local clone",
                result.size(), result.size());
        return result;
    }
}
