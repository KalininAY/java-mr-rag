package com.example.mrrag.review;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.GitLabLocalSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.spi.ChangeGroupEnrichmentPort;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

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
                mr.getSourceBranch(), mr.getSourceBranch());
        // --- Parallel AST graph build ---
        log.info("Building AST graphs in parallel...");
        log.info("Both branches cloned successfully: source={}, target={}",
// 15:12:00
// 2026-04-08 15:32:46.216 DEBUG 7224 --- [onPool-worker-2] s.support.compiler.jdt.JDTTreeBuilder    : Could not find declaration for variable Assertions at (D:/PROJS/java-mr-rag/mr-rag-workspace/EPVV-mr765/to-master-2026-04-08_12-30-31-243/src/test/java/suites/AllureTest.java:87).
// 2026-04-08 15:32:51.704  INFO 7224 --- [onPool-worker-2] c.example.mrrag.graph.GraphBuilderImpl   : doBuildGraphFromModel: running 15 passes in parallel via ForkJoinPool
// 2026-04-08 15:34:20.491  INFO 7224 --- [onPool-worker-1] c.example.mrrag.graph.GraphBuilderImpl   : AST graph built: 45080 nodes, 6002 edge-sources
// 2026-04-08 15:34:21.042 DEBUG 7224 --- [onPool-worker-4] c.e.m.graph.raw.ProjectGraphCacheStore   : Saved 1 segment(s) — bundle: mr-rag-workspace\graph-cache\from-C4548_part7-2026-04-08_12-30-31-103___C4548_part7___dc3dd9f, global deps: mr-rag-workspace\graph-cache\deps
// 2026-04-08 15:34:21.042 DEBUG 7224 --- [onPool-worker-4] c.example.mrrag.graph.GraphBuilderImpl   : buildGraph: saved to disk cache for ProjectKey[projectRoot=D:\PROJS\java-mr-rag\mr-rag-workspace\EPVV-mr765\from-C4548_part7-2026-04-08_12-30-31-103, fingerprint=git:dc3dd9f3cf28cb2a1ea8e0aba141ddb16710c05b]
// 2026-04-08 15:35:33.601  INFO 7224 --- [onPool-worker-2] c.example.mrrag.graph.GraphBuilderImpl   : AST graph built: 45437 nodes, 6044 edge-sources
// 2026-04-08 15:35:33.601  INFO 7224 --- [nio-8080-exec-2] com.example.mrrag.review.ReviewService   : Both AST graphs built successfully

// 2026-04-08 16:48:41
// 2026-04-08 16:54:15.890  INFO 5304 --- [onPool-worker-6] c.example.mrrag.graph.GraphBuilderImpl   : doBuildGraphFromModel: running 15 passes in parallel via ForkJoinPool
// 2026-04-08 16:53:49.023  INFO 5304 --- [onPool-worker-2] c.example.mrrag.graph.GraphBuilderImpl   : doBuildGraphFromSources: merged graph — 45531 nodes, 6074 edge-sources
// 2026-04-08 16:54:41.365  INFO 5304 --- [onPool-worker-6] c.example.mrrag.graph.GraphBuilderImpl   : AST graph built: 7385 nodes, 506 edge-sources
// 2026-04-08 16:54:41.783  INFO 5304 --- [onPool-worker-1] c.example.mrrag.graph.GraphBuilderImpl   : doBuildGraphFromSources: merged graph — 45172 nodes, 6032 edge-sources

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
        List<Diff> rawDiffs = repoGateway.getMrDiffs(request.namespace(), request.repo(), request.mrIid(), null);
        List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
        log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

        List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceGraph, targetGraph);
        log.info("After semantic filter: {} lines ({} removed as MOVED)",
                changedLines.size(), rawLines.size() - changedLines.size());

        List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceGraph);
        log.info("Grouped into {} change groups", groups.size());

        //TODO дальше не работает, поскольку загружается из кэша граф и в провайдерах нет указания на директорию
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
