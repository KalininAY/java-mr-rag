package com.example.mrrag.app.source;

import com.example.mrrag.commons.source.JavaSourceLoader;
import com.example.mrrag.commons.source.VirtualSource;
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
 * {@link JavaSourceLoader} implementation that downloads {@code .java} files
 * from a GitLab repository via the GitLab4J API — <strong>no {@code git clone}</strong>.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Walk the repository tree via {@code RepositoryApi.getTree(recursive=true)},
 *       collecting every {@code .java} path.</li>
 *   <li>Download each file's raw content via {@code RepositoryFileApi.getRawFile()}
 *       and wrap it in a {@link VirtualSource}.</li>
 * </ol>
 *
 * <p>Instantiate per-request:
 * <pre>{@code
 *   JavaSourceLoader loader = new GitLabSourceLoader(gitLabApi, projectId, ref);
 *   List<VirtualSource> sources = loader.loadSources();
 * }</pre>
 *
 * <p>{@code ref} accepts a branch name, tag, or full commit SHA.
 */
@Slf4j
public class GitLabSourceLoader implements JavaSourceLoader {

    private final GitLabApi gitLabApi;
    private final long      projectId;
    /** Branch name, tag, or commit SHA. */
    private final String    ref;

    public GitLabSourceLoader(GitLabApi gitLabApi, long projectId, String ref) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
        this.ref       = ref;
    }

    @Override
    public List<VirtualSource> loadSources() throws GitLabApiException, IOException {
        log.info("Fetching Java source tree from GitLab: projectId={}, ref={}", projectId, ref);

        List<TreeItem> tree = gitLabApi.getRepositoryApi()
                .getTree(projectId, null, ref, true); // recursive = true

        List<String> javaPaths = tree.stream()
                .filter(item -> item.getType() == TreeItem.Type.BLOB)
                .map(TreeItem::getPath)
                .filter(path -> path.endsWith(".java"))
                .toList();

        log.info("Found {} .java files in project {} at ref={}", javaPaths.size(), projectId, ref);

        List<VirtualSource> sources = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                String content = fetchRaw(filePath);
                sources.add(new VirtualSource(filePath, content));
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
