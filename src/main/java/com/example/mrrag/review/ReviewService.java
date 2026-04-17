package com.example.mrrag.review;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.GitLabLocalSourceProvider;
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
 *   <li>Clone source branch → &lt;project&gt;-mr&lt;id&gt;/from-&lt;branch&gt;-&lt;ts&gt; (parallel with target)</li>
 *   <li>Clone target branch → &lt;project&gt;-mr&lt;id&gt;/to-&lt;branch&gt;-&lt;ts&gt;   (parallel with source)</li>
 *   <li>Build AST graphs for both branches (parallel)</li>
 *   <li>Parse git diff</li>
 *   <li><strong>Save snapshot to disk</strong> (projects + diffs) via {@link ReviewDataPersistenceService}</li>
 *   <li>Run RAG pipeline ({@link ContextPipeline}) → classify, find classes/nodes,
 *       collect context per change type, build {@link GroupRepresentation}s</li>
 *   <li>Cleanup both temp dirs</li>
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

        MergeRequest mr = repoGateway.getMergeRequest(request.namespace(), request.repo(), request.mrIid(), null);

        // --- Parallel clone ---
        log.info("Cloning source branch '{}' and target branch '{}' in parallel...",
                mr.getSourceBranch(), mr.getTargetBranch());

        GitLabLocalSourceProvider sourceProvider = new GitLabLocalSourceProvider(
                repoGateway,
                new RemoteProjectRequest(request.namespace(), request.repo(), mr.getSourceBranch(), null, null, true));
        GitLabLocalSourceProvider targetProvider = new GitLabLocalSourceProvider(
                repoGateway,
                new RemoteProjectRequest(request.namespace(), request.repo(), mr.getTargetBranch(), null, null, true));

        log.info("Both branches cloned successfully: source={}, target={}",
                mr.getSourceBranch(), mr.getTargetBranch());

        // --- Parallel AST graph build ---
        log.info("Building AST graphs in parallel...");

        CompletableFuture<ProjectGraph> sourceGraphFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(sourceProvider, false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build source AST graph", e);
            }
        });

        CompletableFuture<ProjectGraph> targetGraphFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(targetProvider, false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build target AST graph", e);
            }
        });

        ProjectGraph sourceGraph = sourceGraphFuture.join();
        ProjectGraph targetGraph = targetGraphFuture.join();
        log.info("Both AST graphs built successfully");

        log.info("Fetching MR diffs...");
        List<Diff> diffs = repoGateway.getMrDiffs(request.namespace(), request.repo(), request.mrIid(), null);

        // --- Save snapshot BEFORE grouping ---
        // localProjectRoot() is non-empty after getSources() was called inside buildGraph()
        Path sourceRoot = sourceProvider.localProjectRoot()
                .orElseThrow(() -> new IllegalStateException("Source project root not available after clone"));
        Path targetRoot = targetProvider.localProjectRoot()
                .orElseThrow(() -> new IllegalStateException("Target project root not available after clone"));
        Path snapshotDir = persistenceService.saveSnapshot(request, mr, diffs, sourceRoot, targetRoot);
        log.info("Review snapshot saved to: {}", snapshotDir);

        log.info("Running ContextPipeline...");
        List<GroupRepresentation> representations = contextPipeline.run(diffs, sourceGraph, targetGraph);
        log.info("ContextPipeline complete: {} representation(s)", representations.size());

        return new ReviewContext(
                request.namespace(), request.repo(), request.mrIid(),
                mr.getSourceBranch(), mr.getTargetBranch(),
                mr.getTitle(), mr.getDescription(),
                representations);
    }
}
