package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.graph.AstGraphI;
import com.example.mrrag.service.source.LocalCloneSourcesProvider;
import com.example.mrrag.service.source.SourcesProvider;
import com.example.mrrag.service.dto.ProjectSourceDto;
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
 * REST endpoint that clones a Git repository into a persistent workspace directory
 * using JGit, builds its AST symbol graph via {@link AstGraphI} and returns
 * build statistics.
 *
 * <pre>
 * POST /api/graph/ingest
 * Content-Type: application/json
 *
 * {
 *   "repoUrl"  : "http://gitlab.example.com/org/repo.git",
 *   "branch"   : "feature/my-branch",
 *   "commit"   : "a1b2c3d4",
 *   "gitToken" : "glpat-xxxxxxxxxxxx",
 *   "force"    : false
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
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

    public record IngestRequest(
            String  repoUrl,
            String  branch,
            String  commit,
            String  gitToken,
            Boolean force
    ) {}

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @PostMapping(
            value    = "/ingest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingest(@RequestBody IngestRequest request) throws Exception {

        long wallStart = System.currentTimeMillis();

        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be blank");
        }

        boolean forceReclone = Boolean.TRUE.equals(request.force());
        String branch  = (request.branch() != null && !request.branch().isBlank())
                         ? request.branch() : "default";
        String commit  = request.commit();

        Path cloneDir  = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            // Invalidate graph for this dir via dto
            SourcesProvider tmpProvider = new LocalCloneSourcesProvider(cloneDir);
            graphService.invalidate(tmpProvider.getProjectSourceDto());
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
                        k -> graph.nodes.values().stream().filter(n -> n.kind() == k).count()));
        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));
        long totalEdges = graph.edgesFrom.values().stream().mapToLong(java.util.List::size).sum();

        return new GraphBuildStats(
                repoUrl, cloneDir.toString(),
                cloneMs, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
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

        try (Git git = cmd.call()) {
            if (commitSha != null && !commitSha.isBlank()) {
                git.checkout().setName(commitSha).call();
            }
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
        @Override
        protected void onUpdate(String taskName, int workCurr, Duration d) {
            log.debug("[jgit] {} {}", taskName, workCurr);
        }
        @Override
        protected void onEndTask(String taskName, int workCurr, Duration d) {
            log.info("[jgit] {} done ({})", taskName, workCurr);
        }
        @Override
        protected void onUpdate(String taskName, int workCurr, int workTotal, int percentDone, Duration d) {
            log.debug("[jgit] {} {}/{} ({}%)", taskName, workCurr, workTotal, percentDone);
        }
        @Override
        protected void onEndTask(String taskName, int workCurr, int workTotal, int percentDone, Duration d) {
            log.info("[jgit] {} done {}/{}", taskName, workCurr, workTotal);
        }
    }
}
