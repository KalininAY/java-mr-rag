package com.example.mrrag.app.controller.requestDTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Запрос на анализ ревью (Merge Request) в проекте GitLab по namespace/repo и mrIid")
public record ReviewRequest(
        @Schema(
                description = "Namespace проекта в GitLab, например group/subgroup",
                example = "mygroup/subgroup",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String namespace,

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
        Long mrIid
) {
}