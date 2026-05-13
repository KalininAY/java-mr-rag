package com.example.mrrag.app.controller.requestDTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Запрос на анализ исходного кода по проекту GitLab")
public record RemoteProjectRequest(
        @Schema(
                description = "Namespace проекта в GitLab, например group/subgroup",
                example = "bugbusters",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String namespace,

        @Schema(
                description = "Название проекта (репозитория) в GitLab",
                example = "epvv",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String repo,

        @Schema(
                description = "Ветка или тег для клонирования. Если не указана — используется ветка по умолчанию (например, main)",
                example = "master"
        )
        String branch,

        @Schema(
                description = "Персональный токен доступа (PAT); если null — используется токен из конфигурации",
                example = "glpat-xxxx"
        )
        String token
) {
}