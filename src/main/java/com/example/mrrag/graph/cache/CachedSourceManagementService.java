package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates clone lifecycle ({@link RepositoryCacheService}) and
 * graph lifecycle ({@link BranchGraphRegistry}, {@link GraphBuilder},
 * {@link GraphPatcher}).
 *
 * <h2>Decision tree</h2>
 * <pre>
 * refreshCache(key)   ← resolves HEAD SHA; git pull only if SHA changed
 *   registry.get(key)
 *     empty?          → full build via GraphBuilder  → registry.put
 *     same SHA?       → return as-is
 *     SHA changed?    → patch (removeFiles + addFiles) → registry.put
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachedSourceManagementService {

    private final RepositoryCacheService cacheService;
    private final BranchGraphRegistry   registry;
    private final GraphBuilder          graphBuilder;
    private final GraphPatcher          patcher;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns an up-to-date {@link ProjectGraph} for the given branch.
     * Always checks the remote HEAD SHA; cheap when nothing changed.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     */
    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {
        BranchCacheEntry entry = cacheService.refreshCache(key, token);
        String currentSha = entry.lastCommitSha();

        log.info("CachedSourceManagementService.getOrBuildGraph: {} @ {}", key, currentSha);

        ProjectSourceProvider fullProvider =
                new LocalProjectSourceProvider(entry.projectKey(), entry.localPath());

        VersionedGraph cached = registry.get(key).orElse(null);

        // Cold start
        if (cached == null) {
            log.info("CachedSourceManagementService: cold start {} @ {}", key, currentSha);
            ProjectGraph graph = graphBuilder.buildGraph(fullProvider);
            registry.put(key, new VersionedGraph(currentSha, graph));
            return graph;
        }

        // Cache hit
        if (cached.commitSha().equals(currentSha)) {
            log.debug("CachedSourceManagementService: cache hit {} @ {}", key, currentSha);
            return cached.graph();
        }

        // Incremental patch
        log.info("CachedSourceManagementService: patching {} {} → {}",
                key, cached.commitSha(), currentSha);

        List<String> changedFiles = cacheService.getChangedFiles(
                key, cached.commitSha(), currentSha, token);
        List<ProjectSource> changedSources = fullProvider.getSources().stream()
                .filter(src -> changedFiles.contains(src.path()))
                .toList();

        log.info("CachedSourceManagementService: {} changed .java files", changedFiles.size());

        patcher.removeFiles(cached.graph(), changedFiles);
        patcher.addFiles(cached.graph(), changedSources,
                fullProvider.localProjectRoot().orElse(null));

        registry.put(key, new VersionedGraph(currentSha, cached.graph()));
        return cached.graph();
    }

    /**
     * Evicts both the clone entry and the graph for the branch.
     * Next call to {@link #getOrBuildGraph} re-clones and rebuilds.
     */
    public void invalidate(ProjectKey key) {
        cacheService.invalidate(key);
        registry.invalidate(key);
        log.info("CachedSourceManagementService.invalidate: evicted {}", key);
    }
}
