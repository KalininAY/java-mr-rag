package com.example.mrrag.review;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.pipeline.ContextPipeline;
import com.example.mrrag.review.snapshot.*;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the review pipeline with snapshot-aware caching.
 *
 * <h2>Decision tree</h2>
 * <pre>
 *  matching snapshot found?
 *  ├─ YES → detectState(snapshotDir)
 *  │         ├─ GRAPHS_READY  → load graphs from disk  → pipeline
 *  │         ├─ SOURCES_READY → buildGraphs(local)     → saveGraphs → pipeline
 *  │         ├─ DIFFS_ONLY    → clone → buildGraphs    → saveGraphs → pipeline
 *  │         └─ EMPTY         → (treat as no snapshot)
 *  └─ NO  → clone → diffs → saveSnapshot → buildGraphs → saveGraphs → pipeline
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final CodeRepositoryGateway repoGateway;
    private final AstGraphService astGraphService;
    private final ContextPipeline contextPipeline;
    private final ReviewDataPersistenceService persistenceService;
    private final ReviewSnapshotReader snapshotReader;

    public ReviewContext buildReviewContext(ReviewRequest request) {
        log.info("buildReviewContext: {}/{} mrIid={}",
                request.namespace(), request.repo(), request.mrIid());

        MergeRequest mr = repoGateway.getMergeRequest(
                request.namespace(), request.repo(), request.mrIid(), null);

        // resolve current HEAD of target branch to validate any cached snapshot
        String currentTargetSha = resolveRemoteHead(request, mr.getTargetBranch());
        log.debug("Current target HEAD: {}", currentTargetSha);

        Optional<Path> existingSnapshot = snapshotReader.findMatchingSnapshot(
                request.namespace(), request.repo(), request.mrIid(), currentTargetSha);

        if (existingSnapshot.isPresent()) {
            Path snapshotDir = existingSnapshot.get();
            SnapshotState state = snapshotReader.detectState(snapshotDir);
            log.info("Reusing snapshot {} (state={})", snapshotDir.getFileName(), state);

            return switch (state) {
                case GRAPHS_READY  -> runFromGraphs(request, mr, snapshotDir);
                case SOURCES_READY -> runFromSources(request, mr, snapshotDir);
                case DIFFS_ONLY    -> runFromDiffs(request, mr, snapshotDir);
                case EMPTY         -> runFull(request, mr);  // nothing reusable
            };
        }

        log.info("No matching snapshot found — running full pipeline");
        return runFull(request, mr);
    }

    // -----------------------------------------------------------------------
    // Pipeline variants
    // -----------------------------------------------------------------------

    /** GRAPHS_READY: load graphs from disk, skip clone + graph build. */
    private ReviewContext runFromGraphs(ReviewRequest request,
                                        MergeRequest mr,
                                        Path snapshotDir) {
        try {
            log.info("[GRAPHS_READY] Loading graphs and diffs from snapshot");
            List<Diff> diffs = snapshotReader.readDiffsFromDir(snapshotDir);
            ProjectGraph sourceGraph = snapshotReader.readSourceGraph(snapshotDir);
            ProjectGraph targetGraph = snapshotReader.readTargetGraph(snapshotDir);
            return runPipeline(request, mr, diffs, sourceGraph, targetGraph);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SOURCES_READY: local dirs exist — build graphs, persist them, run pipeline. */
    private ReviewContext runFromSources(ReviewRequest request,
                                         MergeRequest mr,
                                         Path snapshotDir) {
        try {
            log.info("[SOURCES_READY] Building graphs from local snapshot sources");
            List<Diff> diffs = snapshotReader.readDiffsFromDir(snapshotDir);
            Path sourceRoot = snapshotReader.sourceRootFromDir(snapshotDir);
            Path targetRoot = snapshotReader.targetRootFromDir(snapshotDir);
            ProjectGraph[] graphs = buildGraphsParallel(sourceRoot, targetRoot);
            persistenceService.saveGraphs(snapshotDir, graphs[0], graphs[1]);
            return runPipeline(request, mr, diffs, graphs[0], graphs[1]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** DIFFS_ONLY: diffs on disk, but no sources — clone, build graphs, persist. */
    private ReviewContext runFromDiffs(ReviewRequest request,
                                       MergeRequest mr,
                                       Path snapshotDir) {
        try {
            log.info("[DIFFS_ONLY] Cloning sources, then building graphs");
            List<Diff> diffs = snapshotReader.readDiffsFromDir(snapshotDir);
            Path[] roots = cloneParallel(request, mr);
            // copy clones into existing snapshot dir
            Path newSnapshotDir = persistenceService.saveSnapshot(
                    request, mr, diffs, roots[0], roots[1]);
            ProjectGraph[] graphs = buildGraphsParallel(roots[0], roots[1]);
            persistenceService.saveGraphs(newSnapshotDir, graphs[0], graphs[1]);
            return runPipeline(request, mr, diffs, graphs[0], graphs[1]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Full pipeline: clone → diffs → saveSnapshot → buildGraphs → saveGraphs → pipeline. */
    private ReviewContext runFull(ReviewRequest request, MergeRequest mr) {
        log.info("[FULL] clone → diffs → snapshot → graphs → pipeline");

        Path[] roots = cloneParallel(request, mr);
        Path sourceRoot = roots[0];
        Path targetRoot = roots[1];

        List<Diff> diffs = repoGateway.getMrDiffs(
                request.namespace(), request.repo(), request.mrIid(), null);
        log.info("Fetched {} diff(s)", diffs.size());

        Path snapshotDir = persistenceService.saveSnapshot(request, mr, diffs, sourceRoot, targetRoot);
        log.info("Snapshot saved: {}", snapshotDir.getFileName());

        ProjectGraph[] graphs = buildGraphsParallel(sourceRoot, targetRoot);
        persistenceService.saveGraphs(snapshotDir, graphs[0], graphs[1]);

        return runPipeline(request, mr, diffs, graphs[0], graphs[1]);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private ReviewContext runPipeline(ReviewRequest request,
                                      MergeRequest mr,
                                      List<Diff> diffs,
                                      ProjectGraph sourceGraph,
                                      ProjectGraph targetGraph) {
        log.info("Running ContextPipeline ({} diffs)...", diffs.size());
        List<GroupRepresentation> representations =
                contextPipeline.run(diffs, sourceGraph, targetGraph);
        log.info("ContextPipeline complete: {} group(s)", representations.size());

        return new ReviewContext(
                request.namespace(), request.repo(), request.mrIid(),
                mr.getSourceBranch(), mr.getTargetBranch(),
                mr.getTitle(), mr.getDescription(),
                representations);
    }

    /** Clones source and target branches in parallel; returns [sourceRoot, targetRoot]. */
    private Path[] cloneParallel(ReviewRequest request, MergeRequest mr) {
        log.info("Cloning source='{}' and target='{}' in parallel...",
                mr.getSourceBranch(), mr.getTargetBranch());
        CompletableFuture<Path> sf = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(request.namespace(), request.repo(),
                        mr.getSourceBranch(), null, true, null));
        CompletableFuture<Path> tf = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(request.namespace(), request.repo(),
                        mr.getTargetBranch(), null, true, null));
        Path sourceRoot = sf.join();
        Path targetRoot = tf.join();
        log.info("Cloned: source={} target={}", sourceRoot, targetRoot);
        return new Path[]{sourceRoot, targetRoot};
    }

    /** Builds source and target graphs in parallel; returns [sourceGraph, targetGraph]. */
    private ProjectGraph[] buildGraphsParallel(Path sourceRoot, Path targetRoot) {
        log.info("Building AST graphs in parallel...");
        CompletableFuture<ProjectGraph> sf = CompletableFuture.supplyAsync(() ->
                astGraphService.buildGraph(new LocalProjectSourceProvider(sourceRoot), false));
        CompletableFuture<ProjectGraph> tf = CompletableFuture.supplyAsync(() ->
                astGraphService.buildGraph(new LocalProjectSourceProvider(targetRoot), false));
        ProjectGraph sourceGraph = sf.join();
        ProjectGraph targetGraph = tf.join();
        log.info("AST graphs built successfully");
        return new ProjectGraph[]{sourceGraph, targetGraph};
    }

    /**
     * Resolves the current HEAD SHA of a remote branch via a shallow clone probe.
     * Falls back to empty string (disables SHA-based cache validation) on any error.
     */
    private String resolveRemoteHead(ReviewRequest request, String branch) {
        try {
            Path tmpClone = repoGateway.cloneProject(
                    request.namespace(), request.repo(), branch, null, false, null);
            String sha = ReviewDataPersistenceService.resolveGitHead(tmpClone);
            // don't clean up — GitLabGateway workspace management handles temp dirs
            return sha;
        } catch (Exception e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", branch, e.getMessage());
            return "";
        }
    }
}
