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
 *  currentTargetSha ← GitLab API (getBranchHeadSha)
 *
 *  matching snapshot found?
 *  ├─ YES → detectState(snapshotDir)
 *  │         ├─ GRAPHS_READY  → load graphs + diffs from disk      → pipeline
 *  │         ├─ SOURCES_READY → buildGraphs(local) → saveGraphs    → pipeline
 *  │         ├─ DIFFS_ONLY    → clone → saveSnapshot → buildGraphs  → pipeline
 *  │         └─ EMPTY         → full pipeline
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

        // ── resolve current HEAD of target branch via GitLab API (no clone needed) ──
        String currentTargetSha = resolveBranchHead(request, mr.getTargetBranch());
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
                case EMPTY         -> runFull(request, mr);
            };
        }

        log.info("No matching snapshot — running full pipeline");
        return runFull(request, mr);
    }

    // -----------------------------------------------------------------------
    // Pipeline variants
    // -----------------------------------------------------------------------

    /** GRAPHS_READY: load graphs + diffs from disk, skip clone + graph build. */
    private ReviewContext runFromGraphs(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[GRAPHS_READY] Loading graphs and diffs from snapshot");
            List<Diff> diffs      = snapshotReader.readDiffsFromDir(snapshotDir);
            ProjectGraph srcGraph = snapshotReader.readSourceGraph(snapshotDir);
            ProjectGraph tgtGraph = snapshotReader.readTargetGraph(snapshotDir);
            return runPipeline(req, mr, diffs, srcGraph, tgtGraph);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** SOURCES_READY: local dirs exist — build graphs, persist, run pipeline. */
    private ReviewContext runFromSources(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[SOURCES_READY] Building graphs from local snapshot sources");
            List<Diff> diffs = snapshotReader.readDiffsFromDir(snapshotDir);
            Path srcRoot = snapshotReader.sourceRootFromDir(snapshotDir);
            Path tgtRoot = snapshotReader.targetRootFromDir(snapshotDir);
            ProjectGraph[] graphs = buildGraphsParallel(srcRoot, tgtRoot);
            persistenceService.saveGraphs(snapshotDir, graphs[0], graphs[1]);
            return runPipeline(req, mr, diffs, graphs[0], graphs[1]);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** DIFFS_ONLY: diffs on disk, sources absent — clone, save snapshot, build graphs. */
    private ReviewContext runFromDiffs(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[DIFFS_ONLY] Cloning sources, building graphs");
            List<Diff> diffs  = snapshotReader.readDiffsFromDir(snapshotDir);
            Path[] roots      = cloneParallel(req, mr);
            Path newSnapshot  = persistenceService.saveSnapshot(req, mr, diffs, roots[0], roots[1]);
            ProjectGraph[] graphs = buildGraphsParallel(roots[0], roots[1]);
            persistenceService.saveGraphs(newSnapshot, graphs[0], graphs[1]);
            return runPipeline(req, mr, diffs, graphs[0], graphs[1]);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Full pipeline: clone → diffs → saveSnapshot → buildGraphs → saveGraphs → pipeline. */
    private ReviewContext runFull(ReviewRequest req, MergeRequest mr) {
        log.info("[FULL] clone → diffs → snapshot → graphs → pipeline");

        Path[] roots = cloneParallel(req, mr);

        List<Diff> diffs = repoGateway.getMrDiffs(
                req.namespace(), req.repo(), req.mrIid(), null);
        log.info("Fetched {} diff(s)", diffs.size());

        Path snapshotDir = persistenceService.saveSnapshot(req, mr, diffs, roots[0], roots[1]);
        log.info("Snapshot saved: {}", snapshotDir.getFileName());

        ProjectGraph[] graphs = buildGraphsParallel(roots[0], roots[1]);
        persistenceService.saveGraphs(snapshotDir, graphs[0], graphs[1]);

        return runPipeline(req, mr, diffs, graphs[0], graphs[1]);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private ReviewContext runPipeline(ReviewRequest req, MergeRequest mr,
                                      List<Diff> diffs,
                                      ProjectGraph srcGraph, ProjectGraph tgtGraph) {
        log.info("Running ContextPipeline ({} diffs)...", diffs.size());
        List<GroupRepresentation> reps = contextPipeline.run(diffs, srcGraph, tgtGraph);
        log.info("ContextPipeline complete: {} group(s)", reps.size());
        return new ReviewContext(
                req.namespace(), req.repo(), req.mrIid(),
                mr.getSourceBranch(), mr.getTargetBranch(),
                mr.getTitle(), mr.getDescription(), reps);
    }

    /** Clones source and target branches in parallel; returns [sourceRoot, targetRoot]. */
    private Path[] cloneParallel(ReviewRequest req, MergeRequest mr) {
        log.info("Cloning source='{}' and target='{}' in parallel...",
                mr.getSourceBranch(), mr.getTargetBranch());
        CompletableFuture<Path> sf = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(req.namespace(), req.repo(),
                        mr.getSourceBranch(), null, true, null));
        CompletableFuture<Path> tf = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(req.namespace(), req.repo(),
                        mr.getTargetBranch(), null, true, null));
        Path src = sf.join();
        Path tgt = tf.join();
        log.info("Cloned: source={} target={}", src, tgt);
        return new Path[]{src, tgt};
    }

    /** Builds source and target graphs in parallel; returns [sourceGraph, targetGraph]. */
    private ProjectGraph[] buildGraphsParallel(Path srcRoot, Path tgtRoot) {
        log.info("Building AST graphs in parallel...");
        CompletableFuture<ProjectGraph> sf = CompletableFuture.supplyAsync(() ->
                astGraphService.buildGraph(new LocalProjectSourceProvider(srcRoot), false));
        CompletableFuture<ProjectGraph> tf = CompletableFuture.supplyAsync(() ->
                astGraphService.buildGraph(new LocalProjectSourceProvider(tgtRoot), false));
        ProjectGraph src = sf.join();
        ProjectGraph tgt = tf.join();
        log.info("AST graphs built");
        return new ProjectGraph[]{src, tgt};
    }

    /**
     * Resolves the current HEAD SHA of a branch via GitLab API.
     * Falls back to empty string (disables SHA-based cache validation) on any error.
     */
    private String resolveBranchHead(ReviewRequest req, String branch) {
        try {
            return repoGateway.getBranchHeadSha(
                    req.namespace(), req.repo(), branch, null);
        } catch (Exception e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", branch, e.getMessage());
            return "";
        }
    }
}
