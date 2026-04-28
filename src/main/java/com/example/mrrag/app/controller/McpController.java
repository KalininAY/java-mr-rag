package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.app.service.ReviewService;
import com.example.mrrag.review.model.ReviewContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP (Model Context Protocol) endpoint.
 *
 * <p>Предназначен для вызова из LLM-агентов: принимает минимальный
 * набор параметров MR и возвращает полный {@link ReviewContext},
 * который агент использует как контекст для ревью.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp")
@Tag(name = "MCP", description = "Model Context Protocol — точка входа для LLM-агентов")
public class McpController {

    private final ReviewService reviewService;

    @Operation(
            summary = "Построить контекст ревью для MR",
            description = """
                    Принимает namespace, repo и mrIid.
                    Возвращает полный ReviewContext: ветки, заголовок MR,
                    список групп изменений с AST-контекстом.
                    Предназначен для вызова из MCP-совместимых LLM-агентов.
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
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ReviewContext.class)
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Некорректный запрос"),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab или построении графа")
            }
    )
    @PostMapping(
            value = "/review",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ReviewContext review(@RequestBody @Valid ReviewRequest request) {
        log.info("McpController.review: {}/{} mrIid={}",
                request.namespace(), request.repo(), request.mrIid());
        return reviewService.buildReviewContext(request);
    }
}
