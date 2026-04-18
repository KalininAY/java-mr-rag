package com.example.mrrag.review;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.pipeline.ContextPipeline;
import com.example.mrrag.review.snapshot.ReviewDataPersistenceService;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full review pipeline:
 * <ol>
 *   <li>Fetch MR from GitLab</li>
 *   <li>Clone source and target branches in parallel</li>
 *   <li>Fetch MR diffs from GitLab (fast, runs after clone)</li>
 *   <li><strong>Save snapshot to disk</strong> — projects + diffs —
 *       via {@link ReviewDataPersistenceService} (before the expensive graph build)</li>
 *   <li>Build AST graphs from local clones in parallel
 *       (uses {@link LocalProjectSourceProvider} so graphs can also be rebuilt
 *       from a saved snapshot without GitLab access)</li>
 *   <li>Run RAG pipeline ({@link ContextPipeline}): classify, enrich, build
 *       {@link GroupRepresentation}s</li>
 *   <li>Return {@link ReviewContext}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final CodeRepositoryGateway repoGateway;
    private final AstGraphService astGraphService;
    private final ContextPipeline contextPipeline;
    private final ReviewDataPersistenceService persistenceService;

    public ReviewContext buildReviewContext(ReviewRequest request) {
        log.info("Building review context for project={} mrIid={}",
                request.namespace() + "/" + request.repo(), request.mrIid());

        MergeRequest mr = repoGateway.getMergeRequest(
                request.namespace(), request.repo(), request.mrIid(), null);

        // ── Step 1: clone both branches in parallel ──────────────────────────
        log.info("Cloning source='{}' and target='{}' in parallel...",
                mr.getSourceBranch(), mr.getTargetBranch());

        CompletableFuture<Path> sourceCloneFuture = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(
                        request.namespace(), request.repo(),
                        mr.getSourceBranch(), null, true, null));

        CompletableFuture<Path> targetCloneFuture = CompletableFuture.supplyAsync(() ->
                repoGateway.cloneProject(
                        request.namespace(), request.repo(),
                        mr.getTargetBranch(), null, true, null));

        Path sourceRoot = sourceCloneFuture.join();
        Path targetRoot = targetCloneFuture.join();
        log.info("Both branches cloned: source={} target={}", sourceRoot, targetRoot);

        // ── Step 2: fetch diffs (fast GitLab API call) ───────────────────────
        log.info("Fetching MR diffs...");
        List<Diff> diffs = repoGateway.getMrDiffs(
                request.namespace(), request.repo(), request.mrIid(), null);
        log.info("Fetched {} diff(s)", diffs.size());

        // ── Step 3: save snapshot BEFORE the expensive graph build ───────────
        Path snapshotDir = persistenceService.saveSnapshot(request, mr, diffs, sourceRoot, targetRoot);
        log.info("Review snapshot saved to: {}", snapshotDir);

        // ── Step 4: build AST graphs from local clones in parallel ───────────
        log.info("Building AST graphs in parallel...");

        CompletableFuture<ProjectGraph> sourceGraphFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(new LocalProjectSourceProvider(sourceRoot), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build source AST graph", e);
            }
        });

        CompletableFuture<ProjectGraph> targetGraphFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(new LocalProjectSourceProvider(targetRoot), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build target AST graph", e);
            }
        });

        ProjectGraph sourceGraph = sourceGraphFuture.join();
        ProjectGraph targetGraph = targetGraphFuture.join();
        log.info("Both AST graphs built successfully");

        // ── Step 5: run grouping pipeline ────────────────────────────────────
        log.info("Running ContextPipeline...");
        List<GroupRepresentation> representations =
                contextPipeline.run(diffs, sourceGraph, targetGraph);
        log.info("ContextPipeline complete: {} representation(s)", representations.size());

        return new ReviewContext(
                request.namespace(), request.repo(), request.mrIid(),
                mr.getSourceBranch(), mr.getTargetBranch(),
                mr.getTitle(), mr.getDescription(),
                representations);
    }
}
