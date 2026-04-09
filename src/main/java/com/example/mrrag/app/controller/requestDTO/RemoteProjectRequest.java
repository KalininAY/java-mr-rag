package com.example.mrrag.app.controller.requestDTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Запрос на анализ исходного кода по проекту GitLab")
public record RemoteProjectRequest(
        @Schema(
            description = "Числовой идентификатор проекта в GitLab",
            example = "123",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        Long projectId,

        @Schema(
            description = "Ревизия репозитория: ветка, тег или SHA коммита",
            example = "main",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String revision,

        @Schema(
            description = "Персональный токен доступа (PAT); если null — используется токен из конфигурации",
            example = "glpat-xxxx"
        )
        String token,

        @Schema(
            description = "true — перечитать/переанализировать, игнорируя кэш/локальные копии",
            defaultValue = "false"
        )
        Boolean force
) {}