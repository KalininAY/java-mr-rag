package com.example.mrrag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
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
import java.time.Instant;
import java.util.List;

/**
 * GitLab integration: fetches MR metadata / diffs and checks out source code.
 *
 * <h2>Isolation strategy</h2>
 * Each call to {@link #checkoutBranch} creates a <em>fresh, uniquely-named
 * directory</em> under the configured workspace so that concurrent requests
 * for different MRs (or even the same MR with different branches) never
 * interfere with each other:
 * <pre>
 *   &lt;workspace&gt;/&lt;projectName&gt;-mr&lt;mrId&gt;-&lt;sanitisedBranch&gt;-&lt;epochMs&gt;
 * </pre>
 * The caller is responsible for deleting the directory after use by calling
 * {@link #cleanup(Path)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final GitLabApi gitLabApi;
    private final Path workspacePath;

    @Value("${app.gitlab.token}")
    private String gitlabToken;

    /** Fetches the MergeRequest object from GitLab. */
    public MergeRequest getMergeRequest(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getMergeRequest(projectId, mrIid);
    }

    /** Fetches the raw diff lines for an MR (all files). */
    public List<Diff> getMrDiffs(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getMergeRequestDiffs(projectId, (int) mrIid);
    }

    /**
     * Clones the repository at the given branch into a unique temporary directory
     * and returns its path.  The directory name is:
     * <pre>
     *   &lt;projectName&gt;-mr&lt;mrIid&gt;-&lt;sanitisedBranch&gt;-&lt;epochMillis&gt;
     * </pre>
     * Call {@link #cleanup(Path)} when the directory is no longer needed.
     *
     * @param projectId GitLab numeric project id
     * @param mrIid     MR internal id (used only for the directory name)
     * @param branch    branch to clone
     * @return path to the freshly-cloned working tree
     */
    public Path checkoutBranch(long projectId, long mrIid, String branch)
            throws GitLabApiException, GitAPIException, IOException {

        var project = gitLabApi.getProjectApi().getProject(projectId);
        String repoUrl  = project.getHttpUrlToRepo();
        String projName = sanitise(project.getName());
        String dirName  = projName + "-mr" + mrIid + "-" + sanitise(branch)
                          + "-" + Instant.now().toEpochMilli();

        Path repoDir = workspacePath.resolve(dirName);
        Files.createDirectories(repoDir);

        log.info("Cloning {} branch '{}' into {}", repoUrl, branch, repoDir);
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
     * Deletes a checkout directory created by {@link #checkoutBranch}.
     * Safe to call even if the path no longer exists.
     */
    public void cleanup(Path repoDir) {
        if (repoDir == null || !Files.exists(repoDir)) return;
        try {
            FileUtils.deleteDirectory(repoDir.toFile());
            log.info("Cleaned up checkout directory: {}", repoDir);
        } catch (IOException e) {
            log.warn("Failed to delete checkout directory {}: {}", repoDir, e.getMessage());
        }
    }

    /** Returns path to a specific file inside a checked-out working tree. */
    public Path getFilePath(Path repoDir, String relativeFilePath) {
        return repoDir.resolve(relativeFilePath);
    }

    // -----------------------------------------------------------------------

    /** Replaces any character that is not a letter, digit, dot or hyphen with '_'. */
    private String sanitise(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9.\\-]", "_");
    }
}
