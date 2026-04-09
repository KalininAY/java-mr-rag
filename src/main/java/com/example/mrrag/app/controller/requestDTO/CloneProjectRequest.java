package com.example.mrrag.app.controller.requestDTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Запрос на клонирование репозитория и анализ исходного кода")
public record CloneProjectRequest(
        @Schema(
            description = "HTTPS‑URL Git‑репозитория",
            example = "https://gitlab.example.com/org/repo.git",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank
        String repoUrl,

        @Schema(
            description = "Ветка или тег для клонирования. Если не указана — используется ветка по умолчанию (например, main)",
            example = "feature/my-branch"
        )
        String branch,

        @Schema(
            description = "SHA коммита для checkout (полный или сокращённый, от 7 символов). Имеет приоритет над branch/tag",
            example = "a1b2c3d4e5f678901234567890abcdef12345678"
        )
        String commit,

        @Schema(
            description = "Персональный токен доступа (PAT); если null — используется токен из конфигурации",
            example = "glpat-xxxx"
        )
        String token,

        @Schema(
            description = "true — пересоздать клон и повторно анализировать; false — периспользовать существующий клон",
            defaultValue = "false"
        )
        Boolean force
) {}