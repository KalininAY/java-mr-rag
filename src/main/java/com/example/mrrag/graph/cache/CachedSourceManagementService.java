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
 * Thin orchestration layer: resolves the up-to-date clone via
 * {@link RepositoryCacheService} then delegates graph lifecycle to
 * {@link BranchGraphRegistry}.
 *
 * <h2>Flow</h2>
 * <pre>
 * refreshCache(key)        ← resolves HEAD SHA; pulls only if SHA changed
 *   └─ registry.getOrBuild()  ← cold start | cache hit | incremental patch
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CachedSourceManagementService {

    private final RepositoryCacheService cacheService;
    private final BranchGraphRegistry   registry;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns an up-to-date {@link ProjectGraph} for the given branch.
     *
     * <p>Always checks the remote HEAD SHA. If unchanged the call is
     * cheap (one API lookup, no pull, no rebuild).
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

        return registry.getOrBuild(
                key,
                currentSha,
                fullProvider,
                () -> changedFilesBetween(key, token, entry),
                () -> loadChangedSources(key, token, entry, fullProvider)
        );
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

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

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
