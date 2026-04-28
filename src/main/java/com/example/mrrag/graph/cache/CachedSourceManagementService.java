package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestration layer that connects {@link RepositoryCacheService} (clone lifecycle)
 * with {@link IncrementalGraphBuilder} (graph lifecycle).
 *
 * <h2>Decision tree</h2>
 * <pre>
 * getOrBuildGraph(key, token)
 *   └─ getOrInit(key)       ← ensures local clone exists, called exactly once
 *       ├─ graph absent?     → full build via fullProvider
 *       └─ graph present?
 *           ├─ same SHA?       → return as-is
 *           └─ SHA changed?    → pull + incremental patch
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachedSourceManagementService {

    private final RepositoryCacheService  cacheService;
    private final BranchGraphRegistry     registry;
    private final IncrementalGraphBuilder incrementalBuilder;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns an up-to-date {@link ProjectGraph} for the given branch.
     *
     * <p>{@link RepositoryCacheService#getOrInit} is called exactly once;
     * the resulting {@link BranchCacheEntry} is reused to build the provider
     * and passed as the current SHA to {@link IncrementalGraphBuilder}.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return fully populated graph
     */
    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {
        BranchCacheEntry entry = cacheService.getOrInit(key, token);
        String currentSha = entry.lastCommitSha();

        log.info("CachedSourceManagementService.getOrBuildGraph: {} @ {}", key, currentSha);

        ProjectSourceProvider fullProvider = new LocalProjectSourceProvider(entry.projectKey(), entry.localPath());

        return incrementalBuilder.getOrBuild(
                key,
                currentSha,
                fullProvider,
                () -> syncAndGetChanges(key, token),
                () -> loadChangedSources(key, token, fullProvider)
        );
    }

    /**
     * Pulls the latest commits and returns changed {@code .java} file paths
     * since the last cached SHA. Empty list means no new commits.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     */
    public List<String> syncAndGetChanges(ProjectKey key, String token) {
        BranchCacheEntry before = cacheService.get(key)
                .orElseThrow(() -> new IllegalStateException(
                        "No cache entry for " + key + " — call getOrBuildGraph first"));
        String oldSha = before.lastCommitSha();

        BranchCacheEntry after = cacheService.refreshCache(key, token);
        String newSha = after.lastCommitSha();

        if (oldSha == null || oldSha.equals(newSha)) {
            log.debug("CachedSourceManagementService.syncAndGetChanges: {} no new commits", key);
            return List.of();
        }

        return cacheService.getChangedFiles(key, oldSha, newSha, token);
    }

    /**
     * Evicts both the clone entry and the graph registry entry for the branch.
     * The next call to {@link #getOrBuildGraph} will re-clone and rebuild from scratch.
     */
    public void invalidate(ProjectKey key) {
        cacheService.invalidate(key);
        registry.invalidate(key);
        log.info("CachedSourceManagementService.invalidate: evicted cache and graph for {}", key);
    }

    private List<ProjectSource> loadChangedSources(ProjectKey key, String token,
                                                   ProjectSourceProvider fullProvider) {
        List<String> changedPaths = syncAndGetChanges(key, token);
        if (changedPaths.isEmpty()) return List.of();
        return fullProvider.getSources().stream()
                .filter(src -> changedPaths.contains(src.path()))
                .toList();
    }
}
