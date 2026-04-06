package com.example.mrrag.review;

import com.example.mrrag.review.model.*;
import com.example.mrrag.review.spi.ChangeGroupEnrichmentPort;
import com.example.mrrag.review.spi.MergeRequestCheckoutPort;
import com.example.mrrag.graph.JavaIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full pipeline:
 * 1.  Fetch MR from GitLab
 * 2.  Clone source branch  → &lt;project&gt;-mr&lt;id&gt;/from-&lt;branch&gt;-&lt;ts&gt;
 * 3.  Clone target branch  → &lt;project&gt;-mr&lt;id&gt;/to-&lt;branch&gt;-&lt;ts&gt;
 * 4.  Build JavaParser indexes for both
 * 5.  Parse git diff
 * 6.  Filter MOVED symbols (SemanticDiffFilter)
 * 7.  Group remaining changes by AST code block
 * 8.  Enrich groups
 * 9.  Cleanup both temp dirs
 * 10. Return ReviewContext
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final MergeRequestCheckoutPort mergeRequestCheckout;
    private final JavaIndexService javaIndexService;
    private final DiffParser diffParser;
    private final SemanticDiffFilter semanticDiffFilter;
    private final ChangeGrouper changeGrouper;
    private final ChangeGroupEnrichmentPort changeGroupEnrichment;

    public ReviewContext buildReviewContext(ReviewRequest request)
            throws Exception {

        log.info("Building review context for project={} mrIid={}",
                request.projectId(), request.mrIid());

        MergeRequest mr = mergeRequestCheckout.getMergeRequest(request.projectId(), request.mrIid());

        Path sourceRepoDir = null;
        Path targetRepoDir = null;
        try {
            // Clone into <project>-mr<id>/from-<branch>-<ts>
            log.info("Cloning source branch: {}", request.sourceBranch());
            sourceRepoDir = mergeRequestCheckout.checkoutBranch(
                    request.projectId(), request.mrIid(), request.sourceBranch(), "from");

            // Clone into <project>-mr<id>/to-<branch>-<ts>
            log.info("Cloning target branch: {}", request.targetBranch());
            targetRepoDir = mergeRequestCheckout.checkoutBranch(
                    request.projectId(), request.mrIid(), request.targetBranch(), "to");

            JavaIndexService.ProjectIndex sourceIndex = javaIndexService.buildIndex(sourceRepoDir);
            JavaIndexService.ProjectIndex targetIndex = javaIndexService.buildIndex(targetRepoDir);

            log.info("Fetching MR diffs...");
            List<Diff> rawDiffs = mergeRequestCheckout.getMrDiffs(request.projectId(), request.mrIid());
            List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
            log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

            List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceIndex, targetIndex);
            log.info("After semantic filter: {} lines ({} removed as MOVED)",
                    changedLines.size(), rawLines.size() - changedLines.size());

            List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceIndex);
            log.info("Grouped into {} change groups", groups.size());

            changeGroupEnrichment.enrich(groups, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir);

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
            javaIndexService.invalidate(sourceRepoDir);
            javaIndexService.invalidate(targetRepoDir);
            mergeRequestCheckout.cleanup(sourceRepoDir);
            mergeRequestCheckout.cleanup(targetRepoDir);
        }
    }

    /** Auto-detect branches from GitLab MR metadata. */
    public ReviewContext buildReviewContext(long projectId, long mrIid)
            throws Exception {
        MergeRequest mr = mergeRequestCheckout.getMergeRequest(projectId, mrIid);
        return buildReviewContext(new ReviewRequest(
                projectId, mrIid,
                mr.getSourceBranch(),
                mr.getTargetBranch()
        ));
    }
}
