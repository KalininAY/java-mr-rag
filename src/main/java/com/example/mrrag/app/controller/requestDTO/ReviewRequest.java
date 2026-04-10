package com.example.mrrag.app.controller.requestDTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Запрос на анализ ревью (Merge Request) в проекте GitLab по owner/repo и mrIid")
public record ReviewRequest(
        @Schema(
                description = "Owner (группа или пользователь) проекта в GitLab, например group/subgroup",
                example = "mygroup/subgroup",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String owner,

        @Schema(
                description = "Название проекта (репозитория) в GitLab",
                example = "myrepo",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String repo,

        @Schema(
                description = "Внутренний идентификатор Merge Request (mrIid)",
                example = "42",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        Long mrIid,

        @Schema(
                description = "Исходная ветка MR (source branch)",
                example = "feature/my-branch",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String sourceBranch,

        @Schema(
                description = "Целевая ветка MR (target branch)",
                example = "main",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String targetBranch
) {}