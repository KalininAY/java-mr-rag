package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.GraphBuildService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.source.LocalCloneProjectSourceProvider;
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
 * using JGit, then builds its AST symbol graph via {@link GraphBuildService}.
 *
 * <pre>
 * POST /api/graph/ingest
 * {
 *   "repoUrl"  : "https://gitlab.example.com/org/repo.git",
 *   "branch"   : "feature/my-branch",
 *   "commit"   : "a1b2c3d4",            // optional — checkout exact commit after clone
 *   "gitToken" : "glpat-xxxxxxxxxxxx",  // optional
 *   "force"    : false
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
public class GraphIngestController {

    private final GraphBuildService graphBuildService;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    @Value("${app.gitlab.token:}")
    private String defaultGitlabToken;

    public GraphIngestController(GraphBuildService graphBuildService) {
        this.graphBuildService = graphBuildService;
    }

    public record IngestRequest(
            String  repoUrl,
            String  branch,
            String  commit,
            String  gitToken,
            Boolean force
    ) {}

    @PostMapping(
            value    = "/ingest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingest(@RequestBody IngestRequest request) throws Exception {

        long wallStart = System.currentTimeMillis();

        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank())
            throw new IllegalArgumentException("repoUrl must not be blank");

        boolean forceReclone = Boolean.TRUE.equals(request.force());
        String branch  = (request.branch() != null && !request.branch().isBlank())
                         ? request.branch() : "default";
        String commit  = request.commit();

        Path cloneDir  = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            FileSystemUtils.deleteRecursively(cloneDir);
            graphBuildService.invalidate(cloneDir.toAbsolutePath().normalize());
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

        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphBuildService.buildGraph(
                new LocalCloneProjectSourceProvider(cloneDir));
        long buildMs  = System.currentTimeMillis() - buildStart;
        long totalMs  = System.currentTimeMillis() - wallStart;

        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.nodes.values().stream()
                                .filter(n -> n.kind() == k).count()));

        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));

        long totalEdges = graph.edgesFrom.values().stream()
                .mapToLong(java.util.List::size).sum();

        return new GraphBuildStats(
                repoUrl, cloneDir.toString(),
                cloneMs, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    // -----------------------------------------------------------------------
    // JGit clone + optional commit checkout
    // -----------------------------------------------------------------------

    private void cloneWithJGit(String repoUrl, String branch, String commitSha,
                               Path targetDir, String token)
            throws GitAPIException, IOException {

        var cmd = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir.toFile())
                .setDepth(1)
                .setCloneAllBranches(false);

        if (branch != null && !branch.isBlank())
            cmd.setBranch("refs/heads/" + branch);

        if (token != null && !token.isBlank())
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("oauth2", token));

        cmd.setProgressMonitor(new Slf4jProgressMonitor());
        log.info("JGit clone: {} branch={} → {}", repoUrl,
                branch != null ? branch : "<default>", targetDir);

        try (Git git = cmd.call()) {
            if (commitSha != null && !commitSha.isBlank()) {
                log.info("Checking out commit {}", commitSha);
                git.checkout().setName(commitSha).call();
                log.info("Checked out commit {}", commitSha);
            }
        }
    }

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

    private static final class Slf4jProgressMonitor extends BatchingProgressMonitor {
        @Override protected void onUpdate(String t, int w, Duration d) { log.debug("[jgit] {} {}", t, w); }
        @Override protected void onEndTask(String t, int w, Duration d) { log.info("[jgit] {} done ({})", t, w); }
        @Override protected void onUpdate(String t, int w, int wt, int pct, Duration d) { log.debug("[jgit] {} {}/{} ({}%)", t, w, wt, pct); }
        @Override protected void onEndTask(String t, int w, int wt, int pct, Duration d) { log.info("[jgit] {} done {}/{}", t, w, wt); }
    }
}
