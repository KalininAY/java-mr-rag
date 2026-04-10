package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.repo.CodeRepositoryException;
import com.example.mrrag.app.repo.TreeItem;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.extern.slf4j.Slf4j;

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
public class GitLabRemoteSourceProvider extends GitLabSourceProvider {

    private final CodeRepositoryGateway gateway;

    public GitLabRemoteSourceProvider(CodeRepositoryGateway gateway, RemoteProjectRequest req) {
        super(req);
        this.gateway = gateway;
    }


    @Override
    public List<ProjectSource> getSources() {
        log.info("Fetching .java tree from GitLab: projectId={}, revision={}", owner, branch);

        List<TreeItem> tree = gateway.getRepositoryTree(owner, repo, branch, token);

        List<String> javaPaths = tree.stream()
                .filter(item -> item.type().equals("BLOB"))
                .map(TreeItem::path)
                .filter(p -> p.endsWith(".java"))
                .toList();

        log.info("Found {} .java files in project {} at ref={}",
                javaPaths.size(), owner, branch);

        List<ProjectSource> sources = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                String fileContent = gateway.getFileContent(owner, repo, branch, filePath, token);
                sources.add(new ProjectSource(filePath, fileContent));
            } catch (CodeRepositoryException ex) {
                log.warn("Skipping {} — fetch error: {}", filePath, ex.getMessage());
            }
        }

        log.info("Loaded {}/{} .java files from GitLab (project={}, ref={})",
                sources.size(), javaPaths.size(), owner, branch);
        return sources;
    }


}
