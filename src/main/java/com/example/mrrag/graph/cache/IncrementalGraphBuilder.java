package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates branch-aware incremental graph builds.
 *
 * <h2>Decision tree per request</h2>
 * <pre>
 * registry.get(branchKey)
 *   empty?  → full build via GraphBuilder  → store in registry
 *   present?
 *     same SHA? → return as-is
 *     new SHA?  → removeFiles + addFiles (patch) → update registry
 * </pre>
 *
 * <p>The {@code changedFilesSupplier} and {@code changedSourcesSupplier}
 * lambdas are evaluated lazily — they are only called when a patch is
 * actually needed (i.e. the SHA changed).  For a cold-start or a cache
 * hit neither supplier is invoked.
 *
 * <p>This service does <em>not</em> replace {@link GraphBuilder} — it
 * sits on top of it and delegates cold-start builds to it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalGraphBuilder {

    private final BranchGraphRegistry registry;
    private final GraphPatcher         patcher;
    private final GraphBuilder         graphBuilder;

    /**
     * Returns an up-to-date {@link ProjectGraph} for the given branch.
     *
     * @param branchKey              identifies the branch slot in the registry
     * @param currentCommitSha       SHA of the commit being analysed right now
     * @param fullProvider           used for a cold-start full build
     * @param changedFilesSupplier   lazy: returns repo-relative paths of changed {@code .java} files
     *                               between the previously cached SHA and {@code currentCommitSha}
     * @param changedSourcesSupplier lazy: returns the new file contents for those paths
     * @return fully populated (or incrementally patched) graph
     */
    public ProjectGraph getOrBuild(
            BranchKey branchKey,
            String currentCommitSha,
            ProjectSourceProvider fullProvider,
            Supplier<List<String>>        changedFilesSupplier,
            Supplier<List<ProjectSource>> changedSourcesSupplier) {

        Optional<VersionedGraph> cached = registry.get(branchKey);

        // ── Cold start ────────────────────────────────────────────────
        if (cached.isEmpty()) {
            log.info("IncrementalGraphBuilder: cold start for {} @ {}", branchKey, currentCommitSha);
            ProjectGraph full = graphBuilder.buildGraph(fullProvider, false);
            registry.put(branchKey, new VersionedGraph(currentCommitSha, full));
            return full;
        }

        VersionedGraph vg = cached.get();

        // ── Cache hit (same SHA) ───────────────────────────────────────
        if (vg.commitSha().equals(currentCommitSha)) {
            log.debug("IncrementalGraphBuilder: cache hit for {} @ {}", branchKey, currentCommitSha);
            return vg.graph();
        }

        // ── Incremental patch (SHA changed) ───────────────────────────
        log.info("IncrementalGraphBuilder: patching {} from {} → {}",
                branchKey, vg.commitSha(), currentCommitSha);

        List<String>        changedFiles   = changedFilesSupplier.get();
        List<ProjectSource> changedSources = changedSourcesSupplier.get();

        log.info("IncrementalGraphBuilder: {} changed .java files to patch", changedFiles.size());

        patcher.removeFiles(vg.graph(), changedFiles);
        patcher.addFiles(vg.graph(), changedSources,
                fullProvider.localProjectRoot().orElse(null));

        registry.put(branchKey, new VersionedGraph(currentCommitSha, vg.graph()));
        return vg.graph();
    }
}
