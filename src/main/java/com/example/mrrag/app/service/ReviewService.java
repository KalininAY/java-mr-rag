package com.example.mrrag.app.service;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.graph.cache.CachedSourceManagementService;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.pipeline.ContextPipeline;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the full review pipeline:
 * <ol>
 *   <li>Fetch MR from GitLab</li>
 *   <li>Obtain AST graphs for source and target branches via
 *       {@link CachedSourceManagementService} (parallel) —
 *       clones only on first call, reuses or patches on subsequent calls</li>
 *   <li>Parse git diff</li>
 *   <li>Run RAG pipeline ({@link ContextPipeline}) → classify, find classes/nodes,
 *       collect context per change type, build {@link GroupRepresentation}s</li>
 *   <li>Return {@link ReviewContext}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final CodeRepositoryGateway         repoGateway;
    private final CachedSourceManagementService cachedService;
    private final ContextPipeline               contextPipeline;

    public ReviewContext buildReviewContext(ReviewRequest request) {
        log.info("Building review context for project={} mrIid={}",
                request.namespace() + "/" + request.repo(), request.mrIid());

        MergeRequest mr = repoGateway.getMergeRequest(
                request.namespace(), request.repo(), request.mrIid(), null);

        ProjectKey sourceKey = new ProjectKey(
                request.namespace(), request.repo(), mr.getSourceBranch());
        ProjectKey targetKey = new ProjectKey(
                request.namespace(), request.repo(), mr.getTargetBranch());

        log.info("Obtaining AST graphs for source='{}' and target='{}' in parallel",
                mr.getSourceBranch(), mr.getTargetBranch());

        // Build / reuse both graphs in parallel
        CompletableFuture<ProjectGraph> sourceGraphFuture = CompletableFuture.supplyAsync(
                () -> cachedService.getOrBuildGraph(sourceKey, null));

        CompletableFuture<ProjectGraph> targetGraphFuture = CompletableFuture.supplyAsync(
                () -> cachedService.getOrBuildGraph(targetKey, null));

        ProjectGraph sourceGraph = sourceGraphFuture.join();
        ProjectGraph targetGraph = targetGraphFuture.join();
        log.info("Both AST graphs ready");

        log.info("Fetching MR diffs...");
        List<Diff> diffs = repoGateway.getMrDiffs(
                request.namespace(), request.repo(), request.mrIid(), null);

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
