package com.example.mrrag.review;

import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.spi.ChangeGroupEnrichmentPort;
import com.example.mrrag.review.spi.MergeRequestCheckoutPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
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

    private final MergeRequestCheckoutPort  mergeRequestCheckout;
    private final AstGraphService           astGraphService;
    private final DiffParser                diffParser;
    private final SemanticDiffFilter        semanticDiffFilter;
    private final ChangeGrouper             changeGrouper;
    private final ChangeGroupEnrichmentPort changeGroupEnrichment;

    public ReviewContext buildReviewContext(ReviewRequest request) throws Exception {
        log.info("Building review context for project={} mrIid={}",
                request.projectId(), request.mrIid());

        MergeRequest mr = mergeRequestCheckout.getMergeRequest(request.projectId(), request.mrIid());

        Path sourceRepoDir = null;
        Path targetRepoDir = null;
        ProjectSourceProvider sourceProvider = null;
        ProjectSourceProvider targetProvider = null;
        try {
            sourceRepoDir = Paths.get("mr-rag-workspace\\EPVV-mr765\\from-C4548_part7-2026-04-08_12-30-31-103");
            targetRepoDir = Paths.get("mr-rag-workspace\\EPVV-mr765\\to-master-2026-04-08_12-30-31-243");
            if (sourceRepoDir == null && targetRepoDir == null) {
                // --- Parallel clone ---
                log.info("Cloning source branch '{}' and target branch '{}' in parallel...",
                        request.sourceBranch(), request.targetBranch());

                CompletableFuture<Path> sourceFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return mergeRequestCheckout.checkoutBranch(
                                request.projectId(), request.mrIid(), request.sourceBranch(), "from");
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to clone source branch: " + request.sourceBranch(), e);
                    }
                });

                CompletableFuture<Path> targetFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return mergeRequestCheckout.checkoutBranch(
                                request.projectId(), request.mrIid(), request.targetBranch(), "to");
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to clone target branch: " + request.targetBranch(), e);
                    }
                });

                sourceRepoDir = sourceFuture.join(); // sourceRepoDir = Paths.get("mr-rag-workspace\EPVV-mr765\from-C4548_part7-2026-04-08_12-30-31-103");
                targetRepoDir = targetFuture.join(); // targetRepoDir = Paths.get("mr-rag-workspace\EPVV-mr765\to-master-2026-04-08_12-30-31-243");

                log.info("Both branches cloned successfully: source={}, target={}",
                        sourceRepoDir, targetRepoDir);
            }
            sourceProvider = new LocalCloneProjectSourceProvider(sourceRepoDir);
            targetProvider = new LocalCloneProjectSourceProvider(targetRepoDir);

            // --- Parallel AST graph build ---
            log.info("Building AST graphs in parallel...");  // 2026-04-08 12:42:08.301  INFO 16284 --- [nio-8080-exec-2] com.example.mrrag.review.ReviewService   : Building AST graphs in parallel...
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

            final ProjectSourceProvider sourceProviderRef = sourceProvider;
            final ProjectSourceProvider targetProviderRef = targetProvider;

            ProjectGraph sourceGraph;
            ProjectGraph targetGraph;
            CompletableFuture<ProjectGraph> sourceGraphFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return astGraphService.buildGraph(sourceProviderRef);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to build source AST graph", e);
                }
            });

            CompletableFuture<ProjectGraph> targetGraphFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return astGraphService.buildGraph(targetProviderRef);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to build target AST graph", e);
                }
            });

            sourceGraph = sourceGraphFuture.join();
            targetGraph = targetGraphFuture.join();

            log.info("Both AST graphs built successfully");

            log.info("Fetching MR diffs...");
            List<Diff> rawDiffs = mergeRequestCheckout.getMrDiffs(request.projectId(), request.mrIid());
            List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
            log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

            List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceGraph, targetGraph);
            log.info("After semantic filter: {} lines ({} removed as MOVED)",
                    changedLines.size(), rawLines.size() - changedLines.size());

            List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceGraph);
            log.info("Grouped into {} change groups", groups.size());

            changeGroupEnrichment.enrich(groups, sourceGraph, targetGraph, sourceRepoDir, targetRepoDir);

            int totalSnippets = groups.stream().mapToInt(g -> g.enrichments().size()).sum();
            int totalSnippetLines = groups.stream()
                    .flatMap(g -> g.enrichments().stream())
                    .mapToInt(s -> s.lines().size()).sum();
            ReviewStats stats = new ReviewStats(
                    changedLines.size(), groups.size(), totalSnippets, totalSnippetLines);

            log.info("Review context built: {} groups, {} snippets, {} snippet lines",
                    groups.size(), totalSnippets, totalSnippetLines);

            return new ReviewContext(
                    request.projectId(), request.mrIid(),
                    request.sourceBranch(), request.targetBranch(),
                    mr.getTitle(), mr.getDescription(),
                    groups, stats
            );

        } finally {
            if (sourceProvider != null)
                astGraphService.invalidate(sourceProvider.projectKey());
            if (targetProvider != null)
                astGraphService.invalidate(targetProvider.projectKey());
            mergeRequestCheckout.cleanup(sourceRepoDir);
            mergeRequestCheckout.cleanup(targetRepoDir);
        }
    }

    /** Auto-detect branches from GitLab MR metadata. */
    public ReviewContext buildReviewContext(long projectId, long mrIid) throws Exception {
        MergeRequest mr = mergeRequestCheckout.getMergeRequest(projectId, mrIid);
        return buildReviewContext(new ReviewRequest(
                projectId, mrIid,
                mr.getSourceBranch(),
                mr.getTargetBranch()
        ));
    }
}
