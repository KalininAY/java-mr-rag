package com.example.mrrag.review.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewRequest(
        @NotNull Long projectId,
        @NotNull Long mrIid,
        @NotBlank String sourceBranch,
        @NotBlank String targetBranch
) {}
