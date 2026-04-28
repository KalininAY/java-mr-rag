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
 *   └─ refreshCache(key)     ← always checks remote HEAD; pulls if SHA changed
 *       ├─ graph absent?      → full build via fullProvider
 *       └─ graph present?
 *           ├─ same SHA?       → return as-is
 *           └─ SHA changed?    → incremental patch
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
     * <p>Always resolves the current HEAD SHA via
     * {@link RepositoryCacheService#refreshCache}: if the SHA has not changed
     * the call is cheap (no pull, no rebuild); if it has changed the clone is
     * pulled and the graph is patched incrementally.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return fully populated graph
     */
    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {
        BranchCacheEntry entry = cacheService.refreshCache(key, token);
        String currentSha = entry.lastCommitSha();

        log.info("CachedSourceManagementService.getOrBuildGraph: {} @ {}", key, currentSha);

        ProjectSourceProvider fullProvider = providerFrom(entry);

        return incrementalBuilder.getOrBuild(
                key,
                currentSha,
                fullProvider,
                () -> changedFilesBetween(key, token, entry),
                () -> loadChangedSources(key, token, entry, fullProvider)
        );
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

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Builds a provider from an already-resolved cache entry — no extra gateway call. */
    private ProjectSourceProvider providerFrom(BranchCacheEntry entry) {
        return new LocalProjectSourceProvider(entry.projectKey(), entry.localPath());
    }

    /**
     * Returns changed {@code .java} paths between the SHA recorded in
     * {@code entry} (= previous HEAD before this request's pull) and the
     * current HEAD stored in the refreshed entry.
     *
     * <p>Called lazily — only when {@link IncrementalGraphBuilder} detects
     * that the SHA has changed.
     */
    private List<String> changedFilesBetween(ProjectKey key, String token,
                                             BranchCacheEntry previousEntry) {
        BranchCacheEntry current = cacheService.get(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Cache entry missing for " + key));
        String oldSha = previousEntry.lastCommitSha();
        String newSha = current.lastCommitSha();

        if (oldSha == null || oldSha.equals(newSha)) return List.of();
        return cacheService.getChangedFiles(key, oldSha, newSha, token);
    }

    private List<ProjectSource> loadChangedSources(ProjectKey key, String token,
                                                   BranchCacheEntry previousEntry,
                                                   ProjectSourceProvider fullProvider) {
        List<String> changedPaths = changedFilesBetween(key, token, previousEntry);
        if (changedPaths.isEmpty()) return List.of();
        return fullProvider.getSources().stream()
                .filter(src -> changedPaths.contains(src.path()))
                .toList();
    }
}
