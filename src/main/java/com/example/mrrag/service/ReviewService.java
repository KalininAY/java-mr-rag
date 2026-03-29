package com.example.mrrag.service;

import com.example.mrrag.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full pipeline:
 * 1.  Fetch MR from GitLab
 * 2.  Clone source branch  → unique temp dir
 * 3.  Clone target branch  → unique temp dir
 * 4.  Build JavaParser indexes for both
 * 5.  Parse git diff
 * 6.  Filter MOVED symbols (SemanticDiffFilter)
 * 7.  Group remaining changes by AST code block
 * 8.  Enrich groups
 * 9.  Cleanup both temp dirs
 * 10. Return ReviewContext
 *
 * <p>Each request works in fully isolated directories, so concurrent requests
 * for different (or even the same) MRs never conflict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final GitLabService gitLabService;
    private final JavaIndexService javaIndexService;
    private final DiffParser diffParser;
    private final SemanticDiffFilter semanticDiffFilter;
    private final ChangeGrouper changeGrouper;
    private final ContextEnricher contextEnricher;

    public ReviewContext buildReviewContext(ReviewRequest request)
            throws GitLabApiException, GitAPIException, IOException {

        log.info("Building review context for project={} mrIid={}",
                request.projectId(), request.mrIid());

        // 1. Fetch MR metadata
        MergeRequest mr = gitLabService.getMergeRequest(request.projectId(), request.mrIid());

        Path sourceRepoDir = null;
        Path targetRepoDir = null;
        try {
            // 2. Clone source branch into unique dir
            log.info("Cloning source branch: {}", request.sourceBranch());
            sourceRepoDir = gitLabService.checkoutBranch(
                    request.projectId(), request.mrIid(), request.sourceBranch());

            // 3. Clone target branch into unique dir
            log.info("Cloning target branch: {}", request.targetBranch());
            targetRepoDir = gitLabService.checkoutBranch(
                    request.projectId(), request.mrIid(), request.targetBranch());

            // 4. Build AST indexes
            JavaIndexService.ProjectIndex sourceIndex = javaIndexService.buildIndex(sourceRepoDir);
            JavaIndexService.ProjectIndex targetIndex = javaIndexService.buildIndex(targetRepoDir);

            // 5. Fetch and parse git diff
            log.info("Fetching MR diffs...");
            List<Diff> rawDiffs = gitLabService.getMrDiffs(request.projectId(), request.mrIid());
            List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
            log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

            // 6. Filter purely MOVED symbols
            List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceIndex, targetIndex);
            log.info("After semantic filter: {} lines ({} removed as MOVED)",
                    changedLines.size(), rawLines.size() - changedLines.size());

            // 7. Group by AST code block
            List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceIndex);
            log.info("Grouped into {} change groups", groups.size());

            // 8. Enrich
            contextEnricher.enrich(groups, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir);

            // 9. Stats
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
            // 10. Always clean up temp dirs — even on exception
            javaIndexService.invalidate(sourceRepoDir);
            javaIndexService.invalidate(targetRepoDir);
            gitLabService.cleanup(sourceRepoDir);
            gitLabService.cleanup(targetRepoDir);
        }
    }

    /** Auto-detect branches from GitLab MR metadata. */
    public ReviewContext buildReviewContext(long projectId, long mrIid)
            throws GitLabApiException, GitAPIException, IOException {
        MergeRequest mr = gitLabService.getMergeRequest(projectId, mrIid);
        return buildReviewContext(new ReviewRequest(
                projectId, mrIid,
                mr.getSourceBranch(),
                mr.getTargetBranch()
        ));
    }
}
