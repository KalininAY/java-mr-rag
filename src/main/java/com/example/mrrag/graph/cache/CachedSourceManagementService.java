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
 * <h2>Flow</h2>
 * <ol>
 *   <li>Get cache entry + resolve latest commit SHA</li>
 *   <li>Cache absent  → clone + full build</li>
 *   <li>Resolve current HEAD SHA for the branch</li>
 *   <li>Compare cached SHA vs current SHA</li>
 *   <li>Equal          → return cached graph</li>
 *   <li>Different      → git pull + incremental patch</li>
 * </ol>
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

    public ProjectGraph getOrBuildGraph(ProjectKey key, String token) {

        // 1. Get existing cache entry and the latest remote commit SHA
        BranchCacheEntry cachedEntry = cacheService.get(key).orElse(null);
        VersionedGraph   cachedGraph = registry.get(key).orElse(null);
        String currentSha = cacheService.resolveCurrentSha(key, token);

        log.info("CachedSourceManagementService.getOrBuildGraph: {} @ {}", key, currentSha);

        // 2. Cache absent -> clone + full build
        if (cachedEntry == null || cachedGraph == null) {
            log.info("CachedSourceManagementService: cold start {} @ {}", key, currentSha);
            BranchCacheEntry entry = cacheService.initCache(key, token);
            ProjectSourceProvider provider =
                    new LocalProjectSourceProvider(entry.projectKey(), entry.localPath());
            ProjectGraph graph = graphBuilder.buildGraph(provider);
            registry.put(key, new VersionedGraph(currentSha, graph));
            return graph;
        }

        // 3+4. Compare cached SHA vs current SHA
        String cachedSha = cachedGraph.commitSha();

        // 5. Equal -> return cached graph
        if (cachedSha.equals(currentSha)) {
            log.debug("CachedSourceManagementService: cache hit {} @ {}", key, currentSha);
            return cachedGraph.graph();
        }

        // 6. Different -> git pull + incremental patch
        log.info("CachedSourceManagementService: patching {} {} → {}", key, cachedSha, currentSha);

        BranchCacheEntry updatedEntry = cacheService.pullCache(key, token);
        ProjectSourceProvider provider =
                new LocalProjectSourceProvider(updatedEntry.projectKey(), updatedEntry.localPath());

        List<String> changedFiles = cacheService.getChangedFiles(key, cachedSha, currentSha, token);
        List<ProjectSource> changedSources = provider.getSources().stream()
                .filter(src -> changedFiles.contains(src.path()))
                .toList();

        log.info("CachedSourceManagementService: {} changed .java files", changedFiles.size());

        patcher.removeFiles(cachedGraph.graph(), changedFiles);
        patcher.addFiles(cachedGraph.graph(), changedSources,
                provider.localProjectRoot().orElse(null));

        registry.put(key, new VersionedGraph(currentSha, cachedGraph.graph()));
        return cachedGraph.graph();
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
