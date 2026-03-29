package com.example.mrrag.service;

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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabService {

    private final GitLabApi gitLabApi;
    private final Path workspacePath;

    @Value("${app.gitlab.token}")
    private String gitlabToken;

    @Value("${app.gitlab.url}")
    private String gitlabUrl;

    /**
     * Fetches the MergeRequest object from GitLab.
     */
    public MergeRequest getMergeRequest(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getMergeRequest(projectId, mrIid);
    }

    /**
     * Fetches the raw diff lines for a MR (all files).
     */
    public List<Diff> getMrDiffs(long projectId, long mrIid) throws GitLabApiException {
        return gitLabApi.getMergeRequestApi().getMergeRequestDiffs(projectId, (int) mrIid);
    }

    /**
     * Ensures the repository is checked out at the given branch in the workspace.
     * If it already exists, fetches and checks out. Otherwise clones.
     *
     * @return path to the local working tree
     */
    public Path checkoutBranch(long projectId, String branch) throws GitLabApiException, GitAPIException, IOException {
        var project = gitLabApi.getProjectApi().getProject(projectId);
        String repoUrl = project.getHttpUrlToRepo();

        Path repoDir = workspacePath.resolve("project-" + projectId);

        var credentials = new UsernamePasswordCredentialsProvider("oauth2", gitlabToken);

        if (Files.exists(repoDir.resolve(".git"))) {
            log.debug("Repo exists at {}, fetching and checking out {}", repoDir, branch);
            try (Git git = Git.open(repoDir.toFile())) {
                git.fetch()
                        .setCredentialsProvider(credentials)
                        .setRemote("origin")
                        .call();
                // Try to checkout existing local branch, or create tracking branch
                try {
                    git.checkout().setName(branch).call();
                } catch (Exception e) {
                    git.checkout()
                            .setCreateBranch(true)
                            .setName(branch)
                            .setStartPoint("origin/" + branch)
                            .call();
                }
                git.pull()
                        .setCredentialsProvider(credentials)
                        .setRemoteBranchName(branch)
                        .call();
            }
        } else {
            log.debug("Cloning {} branch {} into {}", repoUrl, branch, repoDir);
            Files.createDirectories(repoDir);
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(repoDir.toFile())
                    .setBranch(branch)
                    .setCredentialsProvider(credentials)
                    .call()
                    .close();
        }

        return repoDir;
    }

    /**
     * Returns path to a specific file in the checked-out repo on the given branch.
     */
    public Path getFilePath(Path repoDir, String relativeFilePath) {
        return repoDir.resolve(relativeFilePath);
    }
}
