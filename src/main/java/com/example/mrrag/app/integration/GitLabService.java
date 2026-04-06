package com.example.mrrag.app.integration;

import com.example.mrrag.review.spi.MergeRequestCheckoutPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GitLab integration: fetches MR metadata / diffs and checks out source code.
 *
 * <h2>Checkout directory layout</h2>
 * <pre>
 *   &lt;workspace&gt;/
 *     &lt;projectName&gt;-mr&lt;mrIid&gt;/
 *       from-&lt;sanitisedBranch&gt;-&lt;yyyy-MM-dd_HH-mm-ss-SSS&gt;/   ← source branch
 *       to-&lt;sanitisedBranch&gt;-&lt;yyyy-MM-dd_HH-mm-ss-SSS&gt;/     ← target branch
 * </pre>
 * Each request gets fresh timestamped leaf directories so concurrent requests
 * never interfere. The MR-level parent dir is created lazily and left on disk
 * (harmless empty dir after cleanup).
 *
 * <p>Call {@link #cleanup(Path)} with the <em>leaf</em> path returned by
 * {@link #checkoutBranch} when the directory is no longer needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService implements MergeRequestCheckoutPort {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final GitLabApi gitLabApi;
    private final Path workspacePath;

    @Value("${app.gitlab.token}")
    private String gitlabToken;

    /** Fetches the MergeRequest object from GitLab. */
    @Override
    public MergeRequest getMergeRequest(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getMergeRequest(projectId, mrIid);
    }

    /** Fetches the raw diff entries for an MR (all files). */
    @Override
    public List<Diff> getMrDiffs(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getDiffs(projectId, mrIid);
    }

    /**
     * Clones {@code branch} into a unique leaf directory and returns its path.
     *
     * @param projectId GitLab numeric project id
     * @param mrIid     MR internal id
     * @param branch    branch name to clone
     * @param role      human-readable role label, e.g. {@code "from"} or {@code "to"}
     * @return path to the freshly-cloned working tree (leaf dir)
     */
    @Override
    public Path checkoutBranch(long projectId, long mrIid, String branch, String role)
            throws GitLabApiException, GitAPIException, IOException {

        var project    = gitLabApi.getProjectApi().getProject(projectId);
        String repoUrl = project.getHttpUrlToRepo();

        String mrDir   = sanitise(project.getName()) + "-mr" + mrIid;
        String leafDir = role + "-" + sanitise(branch)
                         + "-" + LocalDateTime.now().format(TS_FMT);

        Path repoDir = workspacePath.resolve(mrDir).resolve(leafDir);
        Files.createDirectories(repoDir);

        log.info("Cloning {} branch '{}' [{}] into {}", repoUrl, branch, role, repoDir);
        var credentials = new UsernamePasswordCredentialsProvider("oauth2", gitlabToken);
        Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(repoDir.toFile())
                .setBranch(branch)
                .setCredentialsProvider(credentials)
                .call()
                .close();

        log.info("Clone complete: {}", repoDir);
        return repoDir;
    }

    /**
     * Deletes a checkout leaf directory. Safe to call with {@code null} or a
     * non-existent path.
     */
    @Override
    public void cleanup(Path repoDir) {
        if (repoDir == null) return;
        if (!Files.exists(repoDir)) {
            log.debug("Cleanup skipped — already removed: {}", repoDir);
            return;
        }
        try {
            Files.deleteIfExists(repoDir);
            log.info("Cleaned up: {}", repoDir);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", repoDir, e.getMessage());
        }
    }

    /** Returns path to a specific file inside a checked-out working tree. */
    public Path getFilePath(Path repoDir, String relativeFilePath) {
        return repoDir.resolve(relativeFilePath);
    }

    /** Replaces characters unsafe for directory names with '_'. */
    private String sanitise(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9.\\-]", "_");
    }
}
