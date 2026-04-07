package com.example.mrrag.app.source;

import com.example.mrrag.graph.raw.ProjectKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.TreeItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * <h2>ProjectKey</h2>
 * <p>This provider <strong>overrides</strong> {@link #projectKey()} because it has
 * no local checkout. The key uses a virtual root path
 * ({@code "/gitlab/<projectId>"}) and the {@code ref} as fingerprint,
 * prefixed with {@code "git:"} when {@code ref} looks like a full SHA
 * (40 hex chars) or {@code "ref:"} otherwise (branch/tag name).
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
    @Getter
    private final long      projectId;
    /** Branch name, tag name, or full / abbreviated commit SHA. */
    private final String    ref;

    public GitLabProjectSourceProvider(GitLabApi gitLabApi, long projectId, String ref) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
        this.ref       = ref;
    }

    /**
     * Returns a stable cache key for this GitLab project + ref combination.
     *
     * <p>Virtual root: {@code /gitlab/<projectId>} (no real directory — just a
     * stable, unique path for use as map key).
     * Fingerprint: {@code "git:<ref>"} when {@code ref} is a 40-char hex SHA,
     * {@code "ref:<ref>"} otherwise (branch or tag — less stable, but usable).
     */
    @Override
    public ProjectKey projectKey() {
        String fingerprint = isFullSha(ref) ? "git:" + ref : "ref:" + ref;
        return new ProjectKey(Path.of("/gitlab/" + projectId), fingerprint);
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

    /** A ref is treated as a full commit SHA when it is exactly 40 hex characters. */
    private static boolean isFullSha(String ref) {
        return ref != null && ref.length() == 40 && ref.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }
}
