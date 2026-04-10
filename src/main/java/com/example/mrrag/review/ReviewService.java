package com.example.mrrag.review;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.GitLabLocalSourceProvider;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.spi.ChangeGroupEnrichmentPort;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates the full review pipeline:
 * <ol>
 *   <li>Fetch MR from GitLab</li>
 *   <li>Clone source branch → &lt;project&gt;-mr&lt;id&gt;/from-&lt;branch&gt;-&lt;ts&gt; (parallel with target)</li>
 *   <li>Clone target branch → &lt;project&gt;-mr&lt;id&gt;/to-&lt;branch&gt;-&lt;ts&gt;   (parallel with source)</li>
 *   <li>Build AST graphs for both branches (parallel)</li>
 *   <li>Parse git diff</li>
 *   <li>Filter MOVED symbols ({@link SemanticDiffFilter})</li>
 *   <li>Group remaining changes by AST code block ({@link ChangeGrouper})</li>
 *   <li>Enrich groups with context snippets</li>
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
    private final DiffParser diffParser;
    private final SemanticDiffFilter semanticDiffFilter;
    private final ChangeGrouper changeGrouper;
    private final ChangeGroupEnrichmentPort changeGroupEnrichment;

    public ReviewContext buildReviewContext(ReviewRequest request) {
        log.info("Building review context for project={} mrIid={}",
                request.owner() + "/" + request.repo(), request.mrIid());

        MergeRequest mr = repoGateway.getMergeRequest(request.owner(), request.repo(), request.mrIid(), null);

        // --- Parallel clone ---
        log.info("Cloning source branch '{}' and target branch '{}' in parallel...",
                mr.getSourceBranch(), mr.getTargetBranch());


        GitLabLocalSourceProvider sourceProvider = new GitLabLocalSourceProvider(
                repoGateway,
                new RemoteProjectRequest(request.owner(), request.repo(), mr.getSourceBranch(), null, null, true));
        GitLabLocalSourceProvider targetProvider = new GitLabLocalSourceProvider(
                repoGateway,
                new RemoteProjectRequest(request.owner(), request.repo(), mr.getTargetBranch(), null, null, true));


        log.info("Both branches cloned successfully: source={}, target={}",
                mr.getSourceBranch(), mr.getSourceBranch());
        // --- Parallel AST graph build ---
        log.info("Building AST graphs in parallel...");


        ExecutorService graphExecutor = Executors.newFixedThreadPool(2);
        ProjectGraph sourceGraph;
        ProjectGraph targetGraph;
        try {
            CompletableFuture<ProjectGraph> sourceGraphFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return astGraphService.buildGraph(sourceProvider);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to build source AST graph", e);
                }
            }, graphExecutor);

            CompletableFuture<ProjectGraph> targetGraphFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return astGraphService.buildGraph(targetProvider);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to build target AST graph", e);
                }
            }, graphExecutor);

            sourceGraph = sourceGraphFuture.join();
            targetGraph = targetGraphFuture.join();
        } finally {
            graphExecutor.shutdown();
        }

        log.info("Both AST graphs built successfully");

        log.info("Fetching MR diffs...");
        List<Diff> rawDiffs = repoGateway.getMrDiffs(request.owner(), request.repo(), request.mrIid(), null);
        List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
        log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

        List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceGraph, targetGraph);
        log.info("After semantic filter: {} lines ({} removed as MOVED)",
                changedLines.size(), rawLines.size() - changedLines.size());

        List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceGraph);
        log.info("Grouped into {} change groups", groups.size());

        changeGroupEnrichment.enrich(groups, sourceGraph, targetGraph, sourceProvider.localProjectRoot().get(), targetProvider.localProjectRoot().get());

        int totalSnippets = groups.stream().mapToInt(g -> g.enrichments().size()).sum();
        int totalSnippetLines = groups.stream()
                .flatMap(g -> g.enrichments().stream())
                .mapToInt(s -> s.lines().size()).sum();
        ReviewStats stats = new ReviewStats(
                changedLines.size(), groups.size(), totalSnippets, totalSnippetLines);

        log.info("Review context built: {} groups, {} snippets, {} snippet lines",
                groups.size(), totalSnippets, totalSnippetLines);

        return new ReviewContext(
                request.repo(), request.mrIid(),
                mr.getSourceBranch(), mr.getTargetBranch(),
                mr.getTitle(), mr.getDescription(),
                groups, stats
        );
    }
}
