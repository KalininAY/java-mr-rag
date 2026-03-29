package com.example.mrrag.controller;

import com.example.mrrag.model.ReviewContext;
import com.example.mrrag.model.ReviewRequest;
import com.example.mrrag.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for MR review context enrichment.
 */
@Slf4j
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class MrReviewController {

    private final ReviewService reviewService;

    /**
     * POST /api/review
     * Build enriched review context from explicit branch names.
     *
     * Body: { projectId, mrIid, sourceBranch, targetBranch }
     */
    @PostMapping
    public ResponseEntity<ReviewContext> buildReview(
            @Valid @RequestBody ReviewRequest request
    ) {
        log.info("POST /api/review project={} mr={}", request.projectId(), request.mrIid());
        try {
            ReviewContext ctx = reviewService.buildReviewContext(request);
            return ResponseEntity.ok(ctx);
        } catch (Exception e) {
            log.error("Failed to build review context", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/review/{projectId}/{mrIid}
     * Auto-detect branches from GitLab MR metadata.
     */
    @GetMapping("/{projectId}/{mrIid}")
    public ResponseEntity<ReviewContext> buildReviewAutoDetect(
            @PathVariable long projectId,
            @PathVariable long mrIid
    ) {
        log.info("GET /api/review/{}/{}", projectId, mrIid);
        try {
            ReviewContext ctx = reviewService.buildReviewContext(projectId, mrIid);
            return ResponseEntity.ok(ctx);
        } catch (Exception e) {
            log.error("Failed to build review context", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /api/health - simple liveness check
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
