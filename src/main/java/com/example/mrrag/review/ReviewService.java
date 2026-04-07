package com.example.mrrag.review;

import com.example.mrrag.graph.AstGraphService;
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
import java.util.List;

/**
 * Orchestrates the full review pipeline:
 * <ol>
 *   <li>Fetch MR from GitLab</li>
 *   <li>Clone source branch  → &lt;project&gt;-mr&lt;id&gt;/from-&lt;branch&gt;-&lt;ts&gt;</li>
 *   <li>Clone target branch  → &lt;project&gt;-mr&lt;id&gt;/to-&lt;branch&gt;-&lt;ts&gt;</li>
 *   <li>Build AST graphs for both clones via {@link AstGraphService}</li>
 *   <li>Parse git diff</li>
 *   <li>Filter MOVED symbols ({@link SemanticDiffFilter})</li>
 *   <li>Group remaining changes by AST code block ({@link ChangeGrouper})</li>
 *   <li>Enrich groups ({@link ChangeGroupEnrichmentPort})</li>
 *   <li>Cleanup both temp dirs</li>
 *   <li>Return {@link ReviewContext}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MergeRequestCheckoutPort mergeRequestCheckout;
    private final AstGraphService          graphService;
    private final DiffParser               diffParser;
    private final SemanticDiffFilter       semanticDiffFilter;
    private final ChangeGrouper            changeGrouper;
    private final ChangeGroupEnrichmentPort changeGroupEnrichment;

    public ReviewContext buildReviewContext(ReviewRequest request) throws Exception {

        log.info("Building review context for project={} mrIid={}",
                request.projectId(), request.mrIid());

        MergeRequest mr = mergeRequestCheckout.getMergeRequest(request.projectId(), request.mrIid());

        Path sourceRepoDir = null;
        Path targetRepoDir = null;
        try {
            log.info("Cloning source branch: {}", request.sourceBranch());
            sourceRepoDir = mergeRequestCheckout.checkoutBranch(
                    request.projectId(), request.mrIid(), request.sourceBranch(), "from");

            log.info("Cloning target branch: {}", request.targetBranch());
            targetRepoDir = mergeRequestCheckout.checkoutBranch(
                    request.projectId(), request.mrIid(), request.targetBranch(), "to");

            log.info("Building AST graphs...");
            ProjectGraph sourceGraph = graphService.buildGraph(sourceRepoDir);
            ProjectGraph targetGraph = graphService.buildGraph(targetRepoDir);

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
                    request.projectId(),
                    request.mrIid(),
                    request.sourceBranch(),
                    request.targetBranch(),
                    mr.getTitle(),
                    mr.getDescription(),
                    groups,
                    stats
            );

        } finally {
            graphService.invalidate(sourceRepoDir);
            graphService.invalidate(targetRepoDir);
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
