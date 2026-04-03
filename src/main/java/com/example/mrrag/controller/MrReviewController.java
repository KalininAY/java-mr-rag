package com.example.mrrag.controller;

import com.example.mrrag.model.ChangeGroup;
import com.example.mrrag.model.ChangeGroupMarkdown;
import com.example.mrrag.model.ReviewContext;
import com.example.mrrag.model.ReviewRequest;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review")
public class MrReviewController {

    private final ReviewService reviewService;
    private final AstGraphService graphService;

    /** Full review context as JSON. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ReviewContext review(@RequestBody ReviewRequest request) throws Exception {
        ReviewContext reviewContext = reviewService.buildReviewContext(request);
        String s = renderContext(reviewContext);
        return reviewContext;
    }

    /** Auto-detect branches from GitLab MR metadata, return JSON. */
    @GetMapping(value = "/{projectId}/{mrIid}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ReviewContext review(@PathVariable long projectId,
                                @PathVariable long mrIid) throws Exception {
        ReviewContext reviewContext = reviewService.buildReviewContext(projectId, mrIid);
        String s = renderContext(reviewContext);
        return reviewContext;
    }

    /**
     * Returns all ChangeGroups rendered as Markdown — convenient for human inspection.
     *
     * <pre>GET /api/review/{projectId}/{mrIid}/markdown</pre>
     */
    @GetMapping(value = "/{projectId}/{mrIid}/markdown",
                produces = MediaType.TEXT_PLAIN_VALUE)
    public String reviewMarkdown(@PathVariable long projectId,
                                 @PathVariable long mrIid) throws Exception {
        return renderContext(reviewService.buildReviewContext(projectId, mrIid));
    }

    // -----------------------------------------------------------------------

    private String renderContext(ReviewContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MR ").append(ctx.mrIid())
          .append(": ").append(ctx.mrTitle()).append("\n");
        sb.append("`").append(ctx.sourceBranch()).append("` \u2192 `")
          .append(ctx.targetBranch()).append("`\n\n");

        sb.append("**Stats:** ")
          .append(ctx.stats().totalChangedLines()).append(" changed lines, ")
          .append(ctx.stats().totalGroups()).append(" groups, ")
          .append(ctx.stats().totalEnrichmentSnippets()).append(" snippets")
          .append(" (").append(ctx.stats().totalEnrichmentLines()).append(" lines)\n\n");

        sb.append("---\n\n");

        for (ChangeGroup group : ctx.groups()) {
            sb.append(ChangeGroupMarkdown.render(group));
            sb.append("---\n\n");
        }
        return sb.toString();
    }
}
