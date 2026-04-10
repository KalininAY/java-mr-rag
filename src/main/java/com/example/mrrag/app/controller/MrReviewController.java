package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.model.ChangeGroupMarkdown;
import com.example.mrrag.review.model.ReviewContext;
import com.example.mrrag.review.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review")
@Tag(name = "Ревью MR", description = "Построение контекста ревью для Merge Request в GitLab с обогащением через AST-граф")
public class MrReviewController {

    private final ReviewService reviewService;

    @Operation(
            summary = "Построить контекст ревью по запросу",
            description = """
                    Принимает явно заданные параметры MR (projectId, mrIid, ветки и т.д.) и строит
                    полный контекст ревью: список групп изменений, обогащённых AST-контекстом из графа.
                    Возвращает структурированный JSON с информацией об изменениях и их зависимостях.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReviewRequest.class)
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Контекст ревью успешно построен",
                            content = @Content(schema = @Schema(implementation = ReviewContext.class))
                    ),
                    @ApiResponse(responseCode = "400", description = "Некорректный запрос"),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab или построении графа")
            }
    )
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ReviewContext review(@RequestBody @Valid ReviewRequest request) {
        ReviewContext reviewContext = reviewService.buildReviewContext(request);
        String s = renderContext(reviewContext);
        return reviewContext;
    }

    @Operation(
            summary = "Контекст ревью в формате Markdown",
            description = """
                    Возвращает все группы изменений MR в виде Markdown-текста.
                    Удобно для быстрой инспекции результата ревью в человекочитаемом формате
                    без разбора JSON-структуры.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReviewRequest.class)
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Markdown-представление контекста ревью",
                            content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                    @ApiResponse(responseCode = "404", description = "Проект или MR не найден"),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab или построении графа")
            }
    )
    @GetMapping(value = "markdown",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String reviewMarkdown(@RequestBody @Valid ReviewRequest request) {
        return renderContext(reviewService.buildReviewContext(request));
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
