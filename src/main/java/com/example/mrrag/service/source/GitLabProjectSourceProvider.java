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
 * {@link ProjectSourceProvider} that downloads {@code .java} files from a
 * GitLab repository via the GitLab4J API — <strong>no {@code git clone}</strong>.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Walk the full repository tree via
 *       {@code RepositoryApi.getTree(recursive = true)}, collecting
 *       every path that ends with {@code .java}.</li>
 *   <li>Download each file's raw bytes via
 *       {@code RepositoryFileApi.getRawFile()} and wrap the UTF-8 text
 *       in a {@link ProjectSource}.</li>
 * </ol>
 *
 * <h2>Ref semantics</h2>
 * <p>{@code ref} is passed verbatim to the GitLab Repository Files API,
 * which accepts a branch name, a tag name, or a full / abbreviated
 * <strong>commit SHA</strong>.  Use any of these to pin the analysis to
 * an exact repository state.
 *
 * <h2>Rate limiting</h2>
 * <p>For large projects (hundreds of Java files) the sequential download
 * loop may take several seconds.  Consider adding retries or a small
 * back-off if you observe HTTP 429 responses.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ProjectSourceProvider provider =
 *     new GitLabProjectSourceProvider(gitLabApi, projectId, "a1b2c3d4");
 * ProjectGraph graph = graphBuildService.buildGraph(provider);
 * }</pre>
 */
@Slf4j
public class GitLabProjectSourceProvider implements ProjectSourceProvider {

    private final GitLabApi gitLabApi;
    private final long      projectId;
    /** Branch name, tag name, or full / abbreviated commit SHA. */
    private final String    ref;

    public GitLabProjectSourceProvider(GitLabApi gitLabApi, long projectId, String ref) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
        this.ref       = ref;
    }

    public long getProjectId() { return projectId; }
    public String getRef()     { return ref; }

    /**
     * Returns a stable cache key in the format {@code "gitlab:<id>@<ref>"}.
     * Example: {@code "gitlab:123@main"} or {@code "gitlab:123@a1b2c3d4"}.
     */
    @Override
    public String projectId() {
        return "gitlab:" + projectId + "@" + ref;
    }

    @Override
    public List<ProjectSource> getSources() throws GitLabApiException, IOException {
        log.info("Fetching .java tree from GitLab: projectId={}, ref={}", projectId, ref);

        List<TreeItem> tree = gitLabApi.getRepositoryApi()
                .getTree(projectId, null, ref, true); // recursive = true

        List<String> javaPaths = tree.stream()
                .filter(item -> item.getType() == TreeItem.Type.BLOB)
                .map(TreeItem::getPath)
                .filter(p -> p.endsWith(".java"))
                .toList();

        log.info("Found {} .java files in project {} at ref={}",
                javaPaths.size(), projectId, ref);

        List<ProjectSource> sources = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                sources.add(new ProjectSource(filePath, fetchRaw(filePath)));
            } catch (Exception ex) {
                log.warn("Skipping {} — fetch error: {}", filePath, ex.getMessage());
            }
        }

        log.info("Loaded {}/{} .java files from GitLab (project={}, ref={})",
                sources.size(), javaPaths.size(), projectId, ref);
        return sources;
    }

    // ------------------------------------------------------------------ helpers

    private String fetchRaw(String filePath) throws GitLabApiException, IOException {
        try (InputStream is = gitLabApi.getRepositoryFileApi()
                .getRawFile(projectId, ref, filePath)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
