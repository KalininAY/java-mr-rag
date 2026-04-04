package com.example.mrrag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Spoon {@link CtModel} for any GitLab project ref (branch / tag / commit SHA)
 * <strong>without cloning the repository</strong>.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Walk the repository tree via {@code RepositoryApi.getTree()} (recursive flag = true),
 *       collecting every {@code .java} path.</li>
 *   <li>Download each file's raw content via {@code RepositoryFileApi.getRawFile()} and wrap
 *       it in a Spoon {@link VirtualFile}.</li>
 *   <li>Build the Spoon {@link CtModel} entirely in memory — no temp directory required.</li>
 * </ol>
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li>GitLab API rate-limits requests. For projects with hundreds of Java files the download
 *       loop may take a few seconds. Consider adding a small sleep or using parallel streams
 *       with a semaphore if needed.</li>
 *   <li>Spoon is run with {@code setNoClasspath(true)} because the dependency jars are not
 *       available via the API. Symbol resolution for external types will be best-effort.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabSpoonLoader {

    private final GitLabApi gitLabApi;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * A lightweight container for a single Java source file fetched from GitLab.
     *
     * @param path    repository-relative path, e.g. {@code "src/main/java/com/example/Foo.java"}
     * @param content raw UTF-8 source text
     */
    public record VirtualSource(String path, String content) {}

    /**
     * Fetches all {@code .java} files for {@code projectId} at {@code ref} and returns
     * them as a list of {@link VirtualSource} records.
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch name, tag, or commit SHA
     * @return list of virtual sources (never {@code null}, may be empty)
     */
    public List<VirtualSource> fetchJavaSources(long projectId, String ref)
            throws GitLabApiException, IOException {

        log.info("Fetching Java source tree from GitLab: projectId={}, ref={}", projectId, ref);

        // Walk the full repository tree (recursive = true)
        List<TreeItem> tree = gitLabApi.getRepositoryApi()
                .getTree(projectId, null, ref, true);

        List<String> javaPaths = tree.stream()
                .filter(item -> item.getType() == TreeItem.Type.BLOB)
                .map(TreeItem::getPath)
                .filter(path -> path.endsWith(".java"))
                .toList();

        log.info("Found {} .java files in project {} at ref {}", javaPaths.size(), projectId, ref);

        List<VirtualSource> sources = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                String content = fetchFileContent(projectId, filePath, ref);
                sources.add(new VirtualSource(filePath, content));
            } catch (Exception ex) {
                log.warn("Skipping file {} — could not fetch: {}", filePath, ex.getMessage());
            }
        }

        log.info("Successfully fetched {}/{} .java files", sources.size(), javaPaths.size());
        return sources;
    }

    /**
     * Builds a Spoon {@link CtModel} from a pre-fetched list of {@link VirtualSource}s.
     *
     * <p>Caller is responsible for obtaining the list (e.g. via {@link #fetchJavaSources}).
     * This separation makes unit-testing easy: supply hand-crafted sources without hitting
     * the GitLab API.
     *
     * @param sources list of virtual Java source files
     * @return a fully built (or partial, on {@link ModelBuildingException}) Spoon model
     */
    public CtModel buildModel(List<VirtualSource> sources) {
        log.info("Building Spoon model from {} virtual sources", sources.size());

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) {
            // older Spoon versions don't have this method — safe to skip
        }

        for (VirtualSource src : sources) {
            // VirtualFile(content, fileName) — fileName is used by Spoon as the
            // "file name" for position reporting; we pass the repo-relative path
            // so that GraphNode.filePath() stays consistent with diff paths.
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        try {
            return launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException — returning partial model. Cause: {}",
                    mbe.getMessage());
            CtModel partial = launcher.getModel();
            if (partial == null) {
                log.error("Spoon returned null model — falling back to empty launcher model");
                return new Launcher().buildModel();
            }
            return partial;
        }
    }

    /**
     * Convenience method: fetch sources then immediately build the model.
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch name, tag, or commit SHA
     * @return Spoon model
     */
    public CtModel fetchAndBuild(long projectId, String ref)
            throws GitLabApiException, IOException {
        List<VirtualSource> sources = fetchJavaSources(projectId, ref);
        return buildModel(sources);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Downloads the raw content of a single repository file using
     * {@code RepositoryFileApi.getRawFile()}. Returns the content decoded as UTF-8.
     */
    private String fetchFileContent(long projectId, String filePath, String ref)
            throws GitLabApiException, IOException {
        try (InputStream is = gitLabApi.getRepositoryFileApi()
                .getRawFile(projectId, ref, filePath)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
