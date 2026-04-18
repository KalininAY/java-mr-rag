package com.example.mrrag.app.repo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
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

@Slf4j
@Validated
@Service
@RequiredArgsConstructor
public class GitLabGateway implements CodeRepositoryGateway {

    private final GitLabApi gitLabApi;

    @Value("${gitlab.url}")
    private String defaultUrl;

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
                try { api.close(); } catch (Exception ignored) {}
            }
        }
    }

    @FunctionalInterface
    private interface GitLabApiAction<T> {
        T execute(GitLabApi api);
    }

    // -----------------------------------------------------------------------

    @Override
    public Path cloneProject(String namespace, String repo, String branch,
                             String commit, boolean force, String token) {
        if (commit == null)
            commit = getLastCommit(namespace, repo, branch, token).getId();

        if (token == null)
            token = defaultToken;

        Git git = null;
        Path path = Path.of(workspaceDir,
                getClonePath(namespace, repo, branch, commit, token));
        if (path.toFile().exists() && !force) return path;
        else if (path.toFile().exists()) deleteDirectory(path.toFile());

        log.debug("Cloning '{}/{}' branch='{}' commit={}", namespace, repo, branch, commit);
        try {
            URIish uri = new URIish(getProjectUrl(defaultUrl, namespace, repo));
            git = Git.cloneRepository()
                    .setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("oauth2", token))
                    .setURI(uri.toString())
                    .setDirectory(path.toFile())
                    .setBranch(branch)
                    .setCloneAllBranches(false)
                    .setDepth(1)
                    .call();

            if (commit != null && !commit.isBlank())
                git.checkout().setName(commit).call();

            log.debug("Cloned '{}/{}' branch='{}' commit={}", namespace, repo, branch, commit);
            return path;
        } catch (GitAPIException | URISyntaxException e) {
            throw new CodeRepositoryException(
                    "Failed to clone project '%s/%s', branch '%s', commit '%s'"
                            .formatted(namespace, repo, branch, commit), e);
        } finally {
            if (git != null) git.close();
        }
    }

    @Override
    public String getBranchHeadSha(String namespace, String repo,
                                   String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi()
                        .getProject(namespace + "/" + repo);
                Commit head = api.getRepositoryApi()
                        .getBranch(project.getId(), branch)
                        .getCommit();
                String sha = head.getId();
                log.debug("HEAD of '{}/{}' branch='{}': {}", namespace, repo, branch, sha);
                return sha;
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get HEAD SHA for '%s/%s' branch '%s'"
                                .formatted(namespace, repo, branch), e);
            }
        });
    }

    @Override
    public MergeRequest getMergeRequest(String namespace, String repo,
                                        long mrIid, String token) {
        return gitLabApi(token, api -> {
            try {
                return api.getMergeRequestApi()
                        .getMergeRequest("%s/%s".formatted(namespace, repo), mrIid);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get merge request '%s' in project '%s/%s'"
                                .formatted(mrIid, namespace, repo), e);
            }
        });
    }

    @Override
    public List<Diff> getMrDiffs(String namespace, String repo,
                                 long mrIid, String token) {
        return gitLabApi(token, api -> {
            try {
                return api.getMergeRequestApi()
                        .getMergeRequestChanges("%s/%s".formatted(namespace, repo), mrIid)
                        .getChanges();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get diffs for merge request '%s' in project '%s/%s'"
                                .formatted(mrIid, namespace, repo), e);
            }
        });
    }

    @Override
    public String getFileContent(String namespace, String repo,
                                 String branch, String filePath, String token) {
        return gitLabApi(token, api -> {
            String projectPath = "%s/%s".formatted(namespace, repo);
            try (InputStream is = api.getRepositoryFileApi()
                    .getRawFile(projectPath, branch, filePath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get file '%s' in '%s/%s' branch '%s'"
                                .formatted(filePath, namespace, repo, branch), e);
            }
        });
    }

    @Override
    public List<TreeItem> getRepositoryTree(String namespace, String repo,
                                            String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                List<org.gitlab4j.api.models.TreeItem> tree = api
                        .getRepositoryApi()
                        .getTree("%s/%s".formatted(namespace, repo), null, branch, true);
                return TreeItem.listFrom(tree);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get repository tree '%s/%s' branch '%s'"
                                .formatted(namespace, repo, branch), e);
            }
        });
    }

    @Override
    public void cleanup(Path repoDir) {
        try {
            deleteDirectory(repoDir.toFile());
        } catch (Exception e) {
            throw new CodeRepositoryException(
                    "Failed to cleanup repository directory " + repoDir, e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private Long getProjectId(String namespace, String repo, String token) {
        return gitLabApi(token, api -> {
            try {
                return api.getProjectApi().getProject(namespace, repo).getId();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed get project '%s/%s'".formatted(namespace, repo), e);
            }
        });
    }

    public String getProjectUrl(String gitLabHost, String namespace, String repo) {
        return gitLabHost + "/" + namespace + "/" + repo + ".git";
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) deleteDirectory(file);
            else if (!file.delete())
                throw new RuntimeException("Failed to delete file: " + file);
        }
        if (!dir.delete())
            throw new RuntimeException("Failed to delete directory: " + dir);
    }

    public String getClonePath(String namespace, String repo,
                               String branch, String commitId, String token) {
        if (commitId == null)
            commitId = getLastCommit(namespace, repo, branch, token).getId();
        String timestamp = getCommitTimestampDirName(namespace, repo, commitId, token);
        String branchSafe  = branch.replaceAll("[^a-zA-Z0-9._-]", "_");
        String commitSafe  = commitId.substring(0, Math.min(7, commitId.length()));
        String namespaceSafe = namespace.replaceAll("[/\\\\:*?\"<>|]", "_");
        return "%s_%s__%s__%s__%s".formatted(namespaceSafe, repo, timestamp, branchSafe, commitSafe);
    }

    public String getCommitTimestampDirName(String namespace, String repo,
                                            String commitId, String token) {
        Commit commit = getCommit(namespace, repo, commitId, token);
        Instant instant = commit.getCommittedDate().toInstant();
        return instant.atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }

    private Commit getLastCommit(String namespace, String repo,
                                 String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi()
                        .getProject(namespace + "/" + repo);
                return api.getRepositoryApi()
                        .getBranch(project.getId(), branch).getCommit();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get last commit '%s/%s' branch '%s'"
                                .formatted(namespace, repo, branch), e);
            }
        });
    }

    private Commit getCommit(String namespace, String repo,
                             String commit, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi()
                        .getProject(namespace + "/" + repo);
                return api.getCommitsApi().getCommit(project.getId(), commit);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get commit '%s' in '%s/%s'"
                                .formatted(commit, namespace, repo), e);
            }
        });
    }
}
