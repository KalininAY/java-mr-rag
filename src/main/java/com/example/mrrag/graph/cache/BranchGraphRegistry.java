package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * In-memory registry of live {@link VersionedGraph}s, one per branch.
 *
 * <p>Owns the full build/patch decision tree:
 * <pre>
 * getOrBuild(key, sha, ...)
 *   empty?     → full build via GraphBuilder   → store
 *   same SHA?  → return as-is
 *   new SHA?   → GraphPatcher.removeFiles + addFiles → update
 * </pre>
 *
 * <p>Lazy suppliers are only evaluated when a patch is needed.
 * Thread-safe: backed by {@link ConcurrentHashMap}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BranchGraphRegistry {

    private final GraphBuilder  graphBuilder;
    private final GraphPatcher  patcher;

    private final ConcurrentMap<ProjectKey, VersionedGraph> cache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    /**
     * Returns an up-to-date {@link ProjectGraph} for the given branch.
     *
     * @param key                    branch identifier
     * @param currentSha             HEAD SHA resolved before this call
     * @param fullProvider           used for a cold-start full build
     * @param changedFilesSupplier   lazy: repo-relative paths of changed {@code .java} files
     * @param changedSourcesSupplier lazy: new file contents for those paths
     */
    public ProjectGraph getOrBuild(
            ProjectKey key,
            String currentSha,
            ProjectSourceProvider fullProvider,
            Supplier<List<String>> changedFilesSupplier,
            Supplier<List<ProjectSource>> changedSourcesSupplier) {

        VersionedGraph vg = cache.get(key);

        // Cold start
        if (vg == null) {
            log.info("BranchGraphRegistry: cold start {} @ {}", key, currentSha);
            ProjectGraph full = graphBuilder.buildGraph(fullProvider);
            cache.put(key, new VersionedGraph(currentSha, full));
            return full;
        }

        // Cache hit
        if (vg.commitSha().equals(currentSha)) {
            log.debug("BranchGraphRegistry: cache hit {} @ {}", key, currentSha);
            return vg.graph();
        }

        // Incremental patch
        log.info("BranchGraphRegistry: patching {} {} → {}", key, vg.commitSha(), currentSha);
        List<String>        changedFiles   = changedFilesSupplier.get();
        List<ProjectSource> changedSources = changedSourcesSupplier.get();
        log.info("BranchGraphRegistry: {} changed .java files", changedFiles.size());

        patcher.removeFiles(vg.graph(), changedFiles);
        patcher.addFiles(vg.graph(), changedSources,
                fullProvider.localProjectRoot().orElse(null));

        cache.put(key, new VersionedGraph(currentSha, vg.graph()));
        return vg.graph();
    }

    // ------------------------------------------------------------------
    // Registry ops
    // ------------------------------------------------------------------

    public Optional<VersionedGraph> get(ProjectKey key) {
        return Optional.ofNullable(cache.get(key));
    }

    public void invalidate(ProjectKey key) {
        if (cache.remove(key) != null)
            log.info("BranchGraphRegistry: evicted {}", key);
    }

    public boolean contains(ProjectKey key) {
        return cache.containsKey(key);
    }

    public int size() {
        return cache.size();
    }
}
