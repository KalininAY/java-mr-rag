package com.example.mrrag.app.repo;

import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Validated
@Service
@RequiredArgsConstructor
public class GitLabGateway implements CodeRepositoryGateway {

    private final GitLabApi gitLabApi;

    @Value("${gitlab.url}")
    private final String defaultUrl;

    @Value("${app.gitlab.token}")
    private String defaultToken;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;


    private <T> T gitLabApi(String token, GitLabApiAction<T> action) {
        GitLabApi api = (token == null || token.equals(defaultToken))
                ? gitLabApi
                : new GitLabApi(defaultUrl, token);
        try {
            return action.execute(api);
        } finally {
            if (api != gitLabApi) {
                try {
                    api.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @FunctionalInterface
    private interface GitLabApiAction<T> {
        T execute(GitLabApi api);
    }

    @Override
    public Path cloneProject(String owner, String repo, String branch, String commit, String token) {
        if (commit == null)
            commit = getLastCommit(owner, repo, branch, token).getId();

        Git git = null;
        Path path = Path.of(workspaceDir, getClonePath(owner, repo, branch, commit, token));
        try {
            URIish uri = new URIish(getProjectUrl(defaultUrl, owner, repo));
            git = Git.cloneRepository()
                    .setURI(uri.toString())
                    .setDirectory(path.toFile())
                    .setBranch(branch)
                    .setCloneAllBranches(false)
                    .call();

            if (commit != null && !commit.isBlank())
                git.checkout()
                        .setName(commit)
                        .call();
            return path;
        } catch (GitAPIException | URISyntaxException e) {
            throw new CodeRepositoryException("Failed to clone project '%s/%s', branch '%s', commit '%s', path '%s'".formatted(owner, repo, branch, commit, path), e);
        } finally {
            if (git != null) git.close();
        }
    }


    @Override
    public MergeRequest getMergeRequest(String owner, String repo, long mrIid, String token) {
        return gitLabApi(token, api -> {
            try {
                Long projectId = getProjectId(owner, repo, token);
                return api.getMergeRequestApi().getMergeRequest(projectId, mrIid);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed to get merge request '%s' in project '%s/%s'".formatted(mrIid, owner, repo), e);
            }
        });
    }

    @Override
    public List<Diff> getMrDiffs(String owner, String repo, long mrIid, String token) {
        return gitLabApi(token, api -> {
            try {
                Long projectId = api.getProjectApi().getProject(owner, repo).getId();
                return api.getMergeRequestApi().getDiffs(projectId, mrIid);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed to get diffs for merge request '%s' in project '%s/%s'".formatted(mrIid, owner, repo), e);
            }
        });
    }

    @Override
    public String getFileContent(String owner, String repo, String branch, String filePath, String token) {
        return gitLabApi(token, api -> {
            Long projectId = getProjectId(owner, repo, token);
            try (InputStream is = api.getRepositoryFileApi()
                    .getRawFile(projectId, branch, filePath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | GitLabApiException e) {
                throw new CodeRepositoryException("Failed to get content file project '%s/%s', branch '%s', filePath = '%s'".formatted(owner, repo, branch, filePath), e);
            }
        });
    }


    @Override
    public List<TreeItem> getRepositoryTree(String owner, String repo, String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                Long projectId = getProjectId(owner, repo, token);
                List<org.gitlab4j.api.models.TreeItem> tree = api
                        .getRepositoryApi()
                        .getTree(projectId, null, branch, true);
                return TreeItem.listFrom(tree);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed get repository tree project '%s/%s', branch '%s'".formatted(owner, repo, branch), e);
            }
        });
    }

    @Override
    public void cleanup(Path repoDir) {
        try {
            deleteDirectory(repoDir.toFile());
        } catch (Exception e) {
            throw new CodeRepositoryException("Failed to cleanup repository directory " + repoDir, e);
        }
    }

    private Long getProjectId(String owner, String repo, String token) {
        return gitLabApi(token, api -> {
            try {
                return api.getProjectApi().getProject(owner, repo).getId();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed get project '%s/%s'".formatted(owner, repo), e);
            }
        });
    }

    public String getProjectUrl(String gitLabHost, String owner, String repo) {
        return gitLabHost + "/" + owner + "/" + repo + ".git";
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                if (!file.delete()) {
                    throw new RuntimeException("Failed to delete file: " + file);
                }
            }
        }
        if (!dir.delete()) {
            throw new RuntimeException("Failed to delete directory: " + dir);
        }
    }

    public String getClonePath(
            String owner,
            String repo,
            String branch,
            String commitId,
            String token
    ) {
        if (commitId == null)
            commitId = getLastCommit(owner, repo, branch, token).getId();

        String timestamp = getCommitTimestampDirName(owner, repo, commitId, token);
        String branchSafe = branch.replaceAll("[^a-zA-Z0-9._-]", "_");
        String commitSafe = commitId.substring(0, Math.min(7, commitId.length()));

        // owner: заменяем '/' на подчёркивания, чтобы не было пути в пути
        String ownerSafe = owner.replaceAll("[/\\\\:*?\"<>|]", "_");

        return String.format("%s_%s__%s__%s__%s", ownerSafe, repo, timestamp, branchSafe, commitSafe);
    }

    public String getCommitTimestampDirName(
            String owner,
            String repo,
            String commitId,
            String token
    ) {
        Commit commit = getCommit(owner, repo, commitId, token);
        Instant instant = commit.getCommittedDate().toInstant();
        return instant.atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

    }

    private Commit getLastCommit(String owner, String repo, String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi().getProject(owner + "/" + repo);
                return api.getRepositoryApi().getBranch(project.getId(), branch).getCommit();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed get commit project '%s/%s', branch '%s'".formatted(owner, repo, branch), e);
            }
        });
    }

    private Commit getCommit(String owner, String repo, String commit, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi().getProject(owner + "/" + repo);
                return api.getCommitsApi().getCommit(project.getId(), commit);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed get commit project '%s/%s', commit '%s'".formatted(owner, repo, commit), e);
            }
        });
    }
}