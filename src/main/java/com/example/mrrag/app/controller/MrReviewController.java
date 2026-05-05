package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.ReviewRequest;
import com.example.mrrag.review.model.GroupRepresentation;
import com.example.mrrag.review.model.ReviewContext;
import com.example.mrrag.app.service.ReviewService;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    public Mono<ReviewContext> review(@RequestBody @Valid ReviewRequest request) {
        return Mono.fromCallable(() -> reviewService.buildReviewContext(request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
