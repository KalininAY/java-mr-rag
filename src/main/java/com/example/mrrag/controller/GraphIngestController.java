package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.model.graph.EdgeKind;
import com.example.mrrag.model.graph.NodeKind;
import com.example.mrrag.model.graph.ProjectGraph;
import com.example.mrrag.service.graph.AstGraphI;
import com.example.mrrag.service.source.LocalCloneSourcesProvider;
import com.example.mrrag.service.source.SourcesProvider;
import com.example.mrrag.service.dto.ProjectSourceDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint that clones a Git repository into a <b>persistent</b>
 * workspace directory using <b>JGit</b> and builds its AST symbol graph.
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@Tag(name = "Граф (клонирование)", description = "Построение AST-графа с клонированием репозитория через JGit")
public class GraphIngestController {

    private final AstGraphI graphService;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    @Value("${app.gitlab.token:}")
    private String defaultGitlabToken;

    public GraphIngestController(AstGraphI graphService) {
        this.graphService = graphService;
    }

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    @Schema(description = "Запрос на клонирование репозитория и построение AST-графа")
    public record IngestRequest(
            @Schema(description = "HTTPS-URL Git-репозитория", example = "https://gitlab.example.com/org/repo.git",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String  repoUrl,
            @Schema(description = "Ветка или тег. Если не указана — используется ветка по умолчанию", example = "feature/my-branch")
            String  branch,
            @Schema(description = "SHA коммита для checkout (полный или сокращённый, от 7 символов)", example = "a1b2c3d4")
            String  commit,
            @Schema(description = "Токен GitLab (PAT). Если не указан — используется токен из конфигурации", example = "glpat-xxxxxxxxxxxx")
            String  gitToken,
            @Schema(description = "true — удалить существующий клон и повторить клонирование",
                    example = "false", defaultValue = "false")
            Boolean force
    ) {}

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @Operation(
        summary = "Клонировать репозиторий и построить граф",
        description = """
            Клонирует Git-репозиторий в персистентную директорию рабочего пространства
            (${app.workspace.dir}/repos/<slug>/<branch>) с помощью JGit,
            затем строит AST-граф символов Java-проекта.
            """,
        requestBody = @RequestBody(
            required = true,
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = IngestRequest.class),
                examples = {
                    @ExampleObject(name = "Минимальный запрос",
                        value = """
                            {
                              "repoUrl": "https://gitlab.example.com/org/repo.git"
                            }"""),
                    @ExampleObject(name = "С веткой и токеном",
                        value = """
                            {
                              "repoUrl": "https://gitlab.example.com/org/repo.git",
                              "branch": "feature/my-branch",
                              "gitToken": "glpat-xxxxxxxxxxxx",
                              "force": false
                            }"""),
                    @ExampleObject(name = "С конкретным коммитом",
                        value = """
                            {
                              "repoUrl": "https://gitlab.example.com/org/repo.git",
                              "branch": "main",
                              "commit": "a1b2c3d",
                              "force": true
                            }""")
                }
            )
        ),
        responses = {
            @ApiResponse(responseCode = "200",
                description = "Граф успешно построен.",
                content = @Content(schema = @Schema(implementation = GraphBuildStats.class))),
            @ApiResponse(responseCode = "400", description = "Некорректный запрос: repoUrl не указан"),
            @ApiResponse(responseCode = "500", description = "Ошибка клонирования или построения графа")
        }
    )
    @PostMapping(
            value    = "/ingest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingest(
            @org.springframework.web.bind.annotation.RequestBody IngestRequest request) throws Exception {

        long wallStart = System.currentTimeMillis();

        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be blank");
        }

        boolean forceReclone = Boolean.TRUE.equals(request.force());
        String branch  = (request.branch() != null && !request.branch().isBlank())
                         ? request.branch() : "default";
        String commit  = request.commit();

        Path cloneDir = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            String projectId = new LocalCloneSourcesProvider(cloneDir)
                    .getProjectSourceDto().projectId();
            graphService.invalidate(projectId);
            FileSystemUtils.deleteRecursively(cloneDir);
            exists = false;
        }

        long cloneMs = 0;
        if (!exists) {
            Files.createDirectories(cloneDir);
            long cloneStart = System.currentTimeMillis();
            try {
                cloneWithJGit(repoUrl, request.branch(), commit, cloneDir,
                              resolveToken(request.gitToken()));
            } catch (Exception e) {
                FileSystemUtils.deleteRecursively(cloneDir);
                throw e;
            }
            cloneMs = System.currentTimeMillis() - cloneStart;
            log.info("Cloned {} (branch={}, commit={}) in {} ms → {}",
                    repoUrl, branch, commit != null ? commit : "HEAD", cloneMs, cloneDir);
        } else {
            log.info("Reusing existing clone: {}", cloneDir);
        }

        SourcesProvider provider = new LocalCloneSourcesProvider(cloneDir);
        ProjectSourceDto dto = provider.getProjectSourceDto();

        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(dto);
        long buildMs  = System.currentTimeMillis() - buildStart;
        long totalMs  = System.currentTimeMillis() - wallStart;

        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.byFile.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(n -> n.kind == k).count()));
        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.edges.stream().filter(e -> e.kind == k).count()));

        return new GraphBuildStats(
                repoUrl, cloneDir.toString(),
                cloneMs, buildMs, totalMs,
                graph.byFile.values().stream().mapToLong(java.util.List::size).sum(),
                graph.edges.size(),
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    // -----------------------------------------------------------------------
    // JGit clone + optional checkout
    // -----------------------------------------------------------------------

    private void cloneWithJGit(String repoUrl, String branch, String commitSha,
                               Path targetDir, String token)
            throws GitAPIException, IOException {

        var cmd = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir.toFile())
                .setDepth(1)
                .setCloneAllBranches(false);

        if (branch != null && !branch.isBlank()) {
            cmd.setBranch("refs/heads/" + branch);
        }
        if (token != null && !token.isBlank()) {
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("oauth2", token));
        }
        cmd.setProgressMonitor(new Slf4jProgressMonitor());
        log.info("JGit clone: {} branch={} → {}", repoUrl,
                branch != null ? branch : "<default>", targetDir);

        try (Git git = cmd.call()) {
            if (commitSha != null && !commitSha.isBlank()) {
                log.info("Checking out commit {}", commitSha);
                git.checkout().setName(commitSha).call();
            }
            log.debug("JGit done, HEAD={}", git.getRepository().resolve("HEAD"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String resolveToken(String requestToken) {
        if (requestToken != null && !requestToken.isBlank()) return requestToken;
        if (defaultGitlabToken != null && !defaultGitlabToken.isBlank()) return defaultGitlabToken;
        return null;
    }

    private Path resolveCloneDir(String repoUrl, String branch) {
        String repoSlug = repoUrl
                .replaceAll(".*/", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-zA-Z0-9_.-]", "_");
        String branchSlug = branch.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return Path.of(workspaceDir, "repos", repoSlug, branchSlug);
    }

    // -----------------------------------------------------------------------
    // JGit progress → SLF4J
    // -----------------------------------------------------------------------

    private static final class Slf4jProgressMonitor extends BatchingProgressMonitor {
        @Override protected void onUpdate(String t, int w, Duration d) {
            log.debug("[jgit] {} {}", t, w);
        }
        @Override protected void onEndTask(String t, int w, Duration d) {
            log.info("[jgit] {} done ({})", t, w);
        }
        @Override protected void onUpdate(String t, int wc, int wt, int pct, Duration d) {
            log.debug("[jgit] {} {}/{} ({}%)", t, wc, wt, pct);
        }
        @Override protected void onEndTask(String t, int wc, int wt, int pct, Duration d) {
            log.info("[jgit] {} done {}/{}", t, wc, wt);
        }
    }
}
