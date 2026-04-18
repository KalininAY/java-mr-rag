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
 *  │         ├─ GRAPHS_READY  → load graphs + diffs from disk           → pipeline
 *  │         ├─ SOURCES_READY → buildGraphs(local) → saveGraphs         → pipeline
 *  │         ├─ DIFFS_ONLY    → clone → enrichWithSources → buildGraphs
 *  │         │                  → saveGraphs                             → pipeline
 *  │         └─ EMPTY         → full pipeline (snapshot dir reused)
 *  └─ NO  → clone → diffs → saveSnapshot → buildGraphs → saveGraphs    → pipeline
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

        // resolve current HEAD of target branch via GitLab API — no clone needed
        String currentTargetSha = resolveBranchHead(request, mr.getTargetBranch());
        log.debug("Current target HEAD: {}", currentTargetSha);

        Optional<Path> existing = snapshotReader.findMatchingSnapshot(
                request.namespace(), request.repo(), request.mrIid(), currentTargetSha);

        if (existing.isPresent()) {
            Path snapshotDir = existing.get();
            SnapshotState state = snapshotReader.detectState(snapshotDir);
            log.info("Reusing snapshot {} (state={})", snapshotDir.getFileName(), state);
            return switch (state) {
                case GRAPHS_READY  -> runFromGraphs(request, mr, snapshotDir);
                case SOURCES_READY -> runFromSources(request, mr, snapshotDir);
                case DIFFS_ONLY    -> runFromDiffs(request, mr, snapshotDir);
                case EMPTY         -> runFull(request, mr, snapshotDir);
            };
        }

        log.info("No matching snapshot — running full pipeline");
        return runFull(request, mr, null);
    }

    // -----------------------------------------------------------------------
    // Pipeline variants
    // -----------------------------------------------------------------------

    /** GRAPHS_READY: load graphs + diffs from disk, skip clone + graph build entirely. */
    private ReviewContext runFromGraphs(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[GRAPHS_READY] Loading graphs and diffs from snapshot");
            List<Diff>   diffs    = snapshotReader.readDiffsFromDir(snapshotDir);
            ProjectGraph srcGraph = snapshotReader.readSourceGraph(snapshotDir);
            ProjectGraph tgtGraph = snapshotReader.readTargetGraph(snapshotDir);
            return runPipeline(req, mr, diffs, srcGraph, tgtGraph);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** SOURCES_READY: source/target dirs exist — build graphs, persist, run. */
    private ReviewContext runFromSources(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[SOURCES_READY] Building graphs from local snapshot sources");
            List<Diff> diffs  = snapshotReader.readDiffsFromDir(snapshotDir);
            Path srcRoot      = snapshotReader.sourceRootFromDir(snapshotDir);
            Path tgtRoot      = snapshotReader.targetRootFromDir(snapshotDir);
            ProjectGraph[] g  = buildGraphsParallel(srcRoot, tgtRoot);
            persistenceService.saveGraphs(snapshotDir, g[0], g[1]);
            return runPipeline(req, mr, diffs, g[0], g[1]);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * DIFFS_ONLY: diffs on disk, sources absent.
     * Clones both branches, enriches the existing snapshot with sources,
     * then builds and persists graphs.
     */
    private ReviewContext runFromDiffs(ReviewRequest req, MergeRequest mr, Path snapshotDir) {
        try {
            log.info("[DIFFS_ONLY] Cloning sources into existing snapshot {}",
                    snapshotDir.getFileName());
            List<Diff> diffs = snapshotReader.readDiffsFromDir(snapshotDir);
            Path[] roots     = cloneParallel(req, mr);
            // restore source/ and target/ into the SAME snapshot dir
            persistenceService.enrichWithSources(snapshotDir, roots[0], roots[1]);
            ProjectGraph[] g = buildGraphsParallel(roots[0], roots[1]);
            persistenceService.saveGraphs(snapshotDir, g[0], g[1]);
            return runPipeline(req, mr, diffs, g[0], g[1]);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * Full pipeline.
     * If {@code existingSnapshotDir} is non-null (state was EMPTY), reuses that directory
     * so we don't accumulate orphaned snapshot dirs.
     * Otherwise creates a new snapshot directory via {@link ReviewDataPersistenceService#saveSnapshot}.
     */
    private ReviewContext runFull(ReviewRequest req, MergeRequest mr,
                                  Path existingSnapshotDir) {
        log.info("[FULL] clone → diffs → snapshot → graphs → pipeline");

        Path[] roots = cloneParallel(req, mr);

        List<Diff> diffs = repoGateway.getMrDiffs(
                req.namespace(), req.repo(), req.mrIid(), null);
        log.info("Fetched {} diff(s)", diffs.size());

        Path snapshotDir;
        if (existingSnapshotDir != null) {
            // EMPTY snapshot exists — enrich it rather than creating a sibling dir
            log.info("Enriching existing EMPTY snapshot: {}", existingSnapshotDir.getFileName());
            persistenceService.enrichWithSources(existingSnapshotDir, roots[0], roots[1]);
            // also write diffs (they were absent in EMPTY state)
            try {
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                        .writeValue(existingSnapshotDir.resolve("diffs.json").toFile(), diffs);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            snapshotDir = existingSnapshotDir;
        } else {
            snapshotDir = persistenceService.saveSnapshot(req, mr, diffs, roots[0], roots[1]);
            log.info("New snapshot created: {}", snapshotDir.getFileName());
        }

        ProjectGraph[] g = buildGraphsParallel(roots[0], roots[1]);
        persistenceService.saveGraphs(snapshotDir, g[0], g[1]);
        return runPipeline(req, mr, diffs, g[0], g[1]);
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

    /** Resolves HEAD SHA of a branch via GitLab API; returns empty string on error. */
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
