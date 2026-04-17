package com.example.mrrag.app.controller;

import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.GroupRepresentation;
import com.example.mrrag.review.pipeline.ContextPipeline;
import com.example.mrrag.review.snapshot.ReviewSnapshotMeta;
import com.example.mrrag.review.snapshot.ReviewSnapshotReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Temporary debug controller for iterating on the grouping/pipeline logic
 * without hitting GitLab.
 * <p>
 * Reads previously saved review snapshots from disk
 * (written by {@link com.example.mrrag.review.snapshot.ReviewDataPersistenceService})
 * and re-runs only the {@link ContextPipeline} step.
 */
@Slf4j
@RestController
@RequestMapping("/debug/review")
@RequiredArgsConstructor
@Tag(name = "Ревью (отладка)",
     description = "Временные эндпоинты для отладки механизма группировки: " +
                   "читают снапшоты с диска и запускают пайплайн без обращения к GitLab")
public class ReviewDebugController {

    private final ReviewSnapshotReader snapshotReader;
    private final AstGraphService astGraphService;
    private final ContextPipeline contextPipeline;

    // ------------------------------------------------------------------
    // GET /debug/review/snapshots
    // ------------------------------------------------------------------

    @Operation(
            summary = "Список снапшотов",
            description = "Возвращает имена всех снапшотов, сохранённых на диске (новейшие первыми).",
            responses = @ApiResponse(responseCode = "200", description = "Список идентификаторов снапшотов")
    )
    @GetMapping("/snapshots")
    public List<String> listSnapshots() throws Exception {
        return snapshotReader.listSnapshotIds();
    }

    // ------------------------------------------------------------------
    // GET /debug/review/snapshots/{id}/meta
    // ------------------------------------------------------------------

    @Operation(
            summary = "Метаданные снапшота",
            description = "Возвращает содержимое meta.json указанного снапшота.",
            parameters = @Parameter(name = "id", description = "Идентификатор снапшота (имя директории)", required = true),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Метаданные снапшота"),
                    @ApiResponse(responseCode = "400", description = "Снапшот не найден")
            }
    )
    @GetMapping("/snapshots/{id}/meta")
    public ReviewSnapshotMeta getMeta(@PathVariable String id) throws Exception {
        return snapshotReader.readMeta(id);
    }

    // ------------------------------------------------------------------
    // GET /debug/review/snapshots/{id}/diffs
    // ------------------------------------------------------------------

    @Operation(
            summary = "Дифы снапшота",
            description = "Возвращает содержимое diffs.json указанного снапшота.",
            parameters = @Parameter(name = "id", description = "Идентификатор снапшота", required = true),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Список дифов"),
                    @ApiResponse(responseCode = "400", description = "Снапшот не найден")
            }
    )
    @GetMapping("/snapshots/{id}/diffs")
    public List<Diff> getDiffs(@PathVariable String id) throws Exception {
        return snapshotReader.readDiffs(id);
    }

    // ------------------------------------------------------------------
    // POST /debug/review/run
    // ------------------------------------------------------------------

    @Operation(
            summary = "Запустить пайплайн группировки из снапшота",
            description = """
                    Читает снапшот с диска (meta.json, diffs.json, source/, target/),
                    строит AST-графы из локальных копий проектов (без GitLab),
                    запускает ContextPipeline и возвращает результат группировки.
                    Удобно для быстрого итерирования над логикой группировки.
                    """,
            parameters = @Parameter(
                    name = "id",
                    description = "Идентификатор снапшота (имя директории в папке snapshots)",
                    required = true
            ),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Результат группировки"),
                    @ApiResponse(responseCode = "400", description = "Снапшот не найден"),
                    @ApiResponse(responseCode = "500", description = "Ошибка при построении графа или запуске пайплайна")
            }
    )
    @PostMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> run(
            @RequestParam
            @Parameter(description = "Идентификатор снапшота", required = true) String id)
            throws Exception {

        log.info("[ReviewDebug] Loading snapshot: {}", id);

        List<Diff> diffs = snapshotReader.readDiffs(id);
        ReviewSnapshotMeta meta = snapshotReader.readMeta(id);
        Path sourceRoot = snapshotReader.sourceRoot(id);
        Path targetRoot = snapshotReader.targetRoot(id);

        log.info("[ReviewDebug] Snapshot: ns={} repo={} mr={} diffs={}",
                meta.namespace(), meta.repo(), meta.mrIid(), diffs.size());
        log.info("[ReviewDebug] Building AST graphs in parallel: source={} target={}",
                sourceRoot, targetRoot);

        // Build both graphs in parallel — same pattern as ReviewService
        CompletableFuture<ProjectGraph> sourceFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(new LocalProjectSourceProvider(sourceRoot), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build source graph from snapshot", e);
            }
        });
        CompletableFuture<ProjectGraph> targetFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return astGraphService.buildGraph(new LocalProjectSourceProvider(targetRoot), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build target graph from snapshot", e);
            }
        });

        ProjectGraph sourceGraph = sourceFuture.join();
        ProjectGraph targetGraph = targetFuture.join();
        log.info("[ReviewDebug] Both graphs built. Running ContextPipeline...");

        List<GroupRepresentation> representations = contextPipeline.run(diffs, sourceGraph, targetGraph);
        log.info("[ReviewDebug] ContextPipeline complete: {} group(s)", representations.size());

        return Map.of(
                "snapshotId",   id,
                "namespace",    meta.namespace(),
                "repo",         meta.repo(),
                "mrIid",        meta.mrIid(),
                "sourceBranch", meta.sourceBranch(),
                "targetBranch", meta.targetBranch(),
                "diffsCount",   diffs.size(),
                "groups",       representations
        );
    }
}
