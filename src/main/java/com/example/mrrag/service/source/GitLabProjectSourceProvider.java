package com.example.mrrag.service.source;

import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.TreeItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ProjectSourceProvider} that downloads {@code .java} files directly
 * from the GitLab Repository Files API — <strong>no {@code git clone}</strong> required.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Walks the repository tree via
 *       {@code RepositoryApi.getTree(projectId, null, ref, recursive=true)} to collect
 *       all {@code .java} paths.</li>
 *   <li>Downloads each file's raw content via
 *       {@code RepositoryFileApi.getRawFile(projectId, ref, path)}.</li>
 *   <li>Returns the content as {@link VirtualSource} records — no temp files created.</li>
 * </ol>
 *
 * <p>{@code ref} accepts a branch name, tag name, or a full / abbreviated
 * <strong>commit SHA</strong>.  GitLab resolves any ref type transparently.
 *
 * <h2>Rate limiting</h2>
 * GitLab API enforces per-user rate limits. For projects with many {@code .java} files
 * consider adding a small sleep between requests or using a GitLab token with higher limits.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProjectSourceProvider provider =
 *     new GitLabProjectSourceProvider(gitLabApi, 123L, "a1b2c3d4");
 * List<VirtualSource> sources = provider.loadSources();
 * }</pre>
 */
@Slf4j
public class GitLabProjectSourceProvider implements ProjectSourceProvider {

    private final GitLabApi gitLabApi;
    private final long      projectId;
    /** Branch name, tag name, or commit SHA (full or abbreviated). */
    private final String    ref;

    public GitLabProjectSourceProvider(GitLabApi gitLabApi, long projectId, String ref) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
        this.ref       = ref;
    }

    /**
     * Cache key: {@code "gitlab:<projectId>@<ref>"}.  Include the commit SHA
     * (rather than a branch name) when you need a fresh graph on every call.
     */
    @Override
    public Object projectKey() {
        return "gitlab:" + projectId + "@" + ref;
    }

    /**
     * Downloads all {@code .java} files for the configured project + ref.
     * Files that cannot be fetched are skipped with a {@code WARN} log entry.
     */
    @Override
    public List<VirtualSource> loadSources() throws GitLabApiException, IOException {
        log.info("[GitLabProjectSourceProvider] loading .java files: projectId={} ref={}",
                projectId, ref);

        List<TreeItem> tree = gitLabApi.getRepositoryApi()
                .getTree(projectId, null, ref, true);

        List<String> javaPaths = tree.stream()
                .filter(item -> item.getType() == TreeItem.Type.BLOB)
                .map(TreeItem::getPath)
                .filter(p -> p.endsWith(".java"))
                .toList();

        log.info("Found {} .java paths in project {} at ref={}", javaPaths.size(), projectId, ref);

        List<VirtualSource> result = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                String content = fetchRaw(filePath);
                result.add(new VirtualSource(filePath, content));
            } catch (Exception ex) {
                log.warn("Skip {} — fetch error: {}", filePath, ex.getMessage());
            }
        }

        log.info("[GitLabProjectSourceProvider] loaded {}/{} files (project={} ref={})",
                result.size(), javaPaths.size(), projectId, ref);
        return result;
    }

    /**
     * In GitLab API mode the paths returned by {@link #loadSources()} are already
     * repository-relative (e.g. {@code "src/main/java/com/example/Foo.java"}),
     * so no root-prefix stripping is needed.
     */
    @Override
    public String rootHint() {
        return "";
    }

    // ------------------------------------------------------------------ helpers

    private String fetchRaw(String filePath) throws GitLabApiException, IOException {
        try (InputStream is = gitLabApi.getRepositoryFileApi()
                .getRawFile(projectId, ref, filePath)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
