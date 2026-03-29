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
 * 1. Fetch MR from GitLab
 * 2. Clone / fetch source and target branches
 * 3. Build JavaParser indexes for both
 * 4. Parse git diff
 * 5. Filter MOVED symbols (SemanticDiffFilter) — removes pure relocations
 * 6. Group remaining changes by AST code block
 * 7. Enrich groups
 * 8. Return ReviewContext
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

        // 2. Checkout source branch
        log.info("Checking out source branch: {}", request.sourceBranch());
        Path sourceRepoDir = gitLabService.checkoutBranch(request.projectId(), request.sourceBranch());

        // 3. Build index for source branch
        javaIndexService.invalidate(sourceRepoDir);
        JavaIndexService.ProjectIndex sourceIndex = javaIndexService.buildIndex(sourceRepoDir);

        // 4. Checkout target branch
        log.info("Checking out target branch: {}", request.targetBranch());
        Path targetRepoDir = gitLabService.checkoutBranch(request.projectId(), request.targetBranch());

        // 5. Build index for target branch
        javaIndexService.invalidate(targetRepoDir);
        JavaIndexService.ProjectIndex targetIndex = javaIndexService.buildIndex(targetRepoDir);

        // 6. Fetch and parse git diff
        log.info("Fetching MR diffs...");
        List<Diff> rawDiffs = gitLabService.getMrDiffs(request.projectId(), request.mrIid());
        List<ChangedLine> rawLines = diffParser.parse(rawDiffs);
        log.info("Parsed {} changed lines from {} file diffs", rawLines.size(), rawDiffs.size());

        // 7. Filter out purely MOVED symbols (git shows them as delete+add, but nothing changed)
        List<ChangedLine> changedLines = semanticDiffFilter.filter(rawLines, sourceIndex, targetIndex);
        log.info("After semantic filter: {} changed lines ({} removed as MOVED)",
                changedLines.size(), rawLines.size() - changedLines.size());

        // 8. Group by AST code block + cross-file AST merge
        List<ChangeGroup> groups = changeGrouper.group(changedLines, sourceIndex);
        log.info("Grouped into {} change groups", groups.size());

        // 9. Enrich
        contextEnricher.enrich(groups, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir);

        // 10. Stats
        int totalSnippets = groups.stream().mapToInt(g -> g.enrichments().size()).sum();
        int totalSnippetLines = groups.stream()
                .flatMap(g -> g.enrichments().stream())
                .mapToInt(s -> s.lines().size()).sum();
        ReviewStats stats = new ReviewStats(
                changedLines.size(), groups.size(), totalSnippets, totalSnippetLines);

        log.info("Review context built: {} groups, {} enrichment snippets, {} enrichment lines",
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
    }

    /**
     * Auto-detect branches from GitLab MR metadata.
     */
    public ReviewContext buildReviewContext(long projectId, long mrIid)
            throws GitLabApiException, GitAPIException, IOException {
        MergeRequest mr = gitLabService.getMergeRequest(projectId, mrIid);
        ReviewRequest request = new ReviewRequest(
                projectId, mrIid,
                mr.getSourceBranch(),
                mr.getTargetBranch()
        );
        return buildReviewContext(request);
    }
}
