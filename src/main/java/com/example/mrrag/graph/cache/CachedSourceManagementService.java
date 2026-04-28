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
 *   └─ getOrInit(key)          ← ensures local clone exists
 *       ├─ graph absent?        → full build
 *       └─ graph present?
 *           ├─ same SHA?          → return as-is
 *           └─ SHA changed?       → pull + incremental patch
 * </pre>
 *
 * <p>Token handling: the GitLab personal-access token is accepted as a
 * parameter and forwarded to {@link RepositoryCacheService}. Callers that
 * use a default/system token may pass {@code null}.
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
     * <ol>
     *   <li>Ensures the branch is cloned locally ({@link RepositoryCacheService#getOrInit}).</li>
     *   <li>Delegates to {@link IncrementalGraphBuilder#getOrBuild} with lazy suppliers
     *       for changed files / changed sources — these are only evaluated when the
     *       cached SHA differs from the current HEAD.</li>
     * </ol>
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return fully populated graph
     */
    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {
        // 1. Ensure clone exists and is up-to-date
        BranchCacheEntry entry = cacheService.getOrInit(key, token);
        String currentSha = entry.lastCommitSha();

        log.info("CachedSourceManagementService.getOrBuildGraph: {} @ {}", key, currentSha);

        // 2. Provider for a cold-start full build
        ProjectSourceProvider fullProvider = provideSourcesForBranch(key, token);

        // 3. Delegate — lazy suppliers are only called when SHA changed
        return incrementalBuilder.getOrBuild(
                key,
                currentSha,
                fullProvider,
                // changedFilesSupplier
                () -> syncAndGetChanges(key, token),
                // changedSourcesSupplier
                () -> loadChangedSources(key, token, fullProvider)
        );
    }

    /**
     * Returns a {@link ProjectSourceProvider} backed by the local clone for this branch.
     *
     * <p>The provider carries the correct {@link ProjectKey} so that the graph layer
     * can identify which branch the sources belong to.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable — used only if clone is absent)
     * @return provider reading from the local clone directory
     */
    public ProjectSourceProvider provideSourcesForBranch(ProjectKey key, String token) {
        BranchCacheEntry entry = cacheService.getOrInit(key, token);
        return new LocalProjectSourceProvider(key, entry.localPath());
    }

    /**
     * Pulls the latest commits for the branch and returns the list of
     * repository-relative {@code .java} file paths that changed since the
     * last cached SHA.
     *
     * <p>After this call the {@link BranchCacheEntry#lastCommitSha()} is
     * updated to the new HEAD.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return changed {@code .java} file paths; empty list if nothing changed
     */
    public List<String> syncAndGetChanges(ProjectKey key, String token) {
        BranchCacheEntry before = cacheService.get(key)
                .orElseThrow(() -> new IllegalStateException(
                        "No cache entry for " + key + " — call getOrBuildGraph first"));
        String oldSha = before.lastCommitSha();

        BranchCacheEntry after = cacheService.refreshCache(key, token);
        String newSha = after.lastCommitSha();

        if (oldSha != null && oldSha.equals(newSha)) {
            log.debug("CachedSourceManagementService.syncAndGetChanges: {} no new commits", key);
            return List.of();
        }

        if (oldSha == null) {
            log.debug("CachedSourceManagementService.syncAndGetChanges: {} first sync, no diff available", key);
            return List.of();
        }

        return cacheService.getChangedFiles(key, oldSha, newSha, token);
    }

    /**
     * Evicts both the clone entry and the graph registry entry for the branch.
     *
     * <p>The next call to {@link #getOrBuildGraph} will re-clone and rebuild from scratch.
     *
     * @param key branch identifier
     */
    public void invalidate(ProjectKey key) {
        cacheService.invalidate(key);
        registry.invalidate(key);
        log.info("CachedSourceManagementService.invalidate: evicted cache and graph for {}", key);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Loads the content of the changed files from the local clone.
     * Only called lazily when the SHA has changed.
     */
    private List<ProjectSource> loadChangedSources(ProjectKey key, String token,
                                                   ProjectSourceProvider fullProvider) {
        List<String> changedPaths = syncAndGetChanges(key, token);
        if (changedPaths.isEmpty()) return List.of();

        // Re-read only the changed files from the local clone
        return fullProvider.getSources().stream()
                .filter(src -> changedPaths.contains(src.path()))
                .toList();
    }
}
