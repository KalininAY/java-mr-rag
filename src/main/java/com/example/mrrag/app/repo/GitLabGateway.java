package com.example.mrrag.app.repo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CompareResults;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /** Open Git handles for cache directories, keyed by absolute path string. */
    private final ConcurrentMap<String, Git> openHandles = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // clone / pull
    // ------------------------------------------------------------------

    @Override
    public Path cloneProject(String namespace, String repo, String branch,
                             String commit, boolean force, String token) {
        if (commit == null)
            commit = getLastCommit(namespace, repo, branch, token).getId();
        if (token == null)
            token = defaultToken;

        Path path = Path.of(workspaceDir,
                getClonePath(namespace, repo, branch, commit, token));

        if (path.toFile().exists() && !force) return path;
        if (path.toFile().exists()) deleteDirectory(path.toFile());

        log.debug("cloneProject: cloning '{}/{}' branch='{}' commit={}",
                namespace, repo, branch, commit);
        Git git = null;
        try {
            git = Git.cloneRepository()
                    .setCredentialsProvider(credentials(token))
                    .setURI(projectUrl(namespace, repo))
                    .setDirectory(path.toFile())
                    .setBranch(branch)
                    .setCloneAllBranches(false)
                    .setDepth(1)
                    .call();
            git.checkout().setName(commit).call();
            return path;
        } catch (GitAPIException | URISyntaxException e) {
            throw new CodeRepositoryException(
                    "Failed to clone '%s/%s' branch='%s' commit='%s'"
                            .formatted(namespace, repo, branch, commit), e);
        } finally {
            if (git != null) git.close();
        }
    }

    /**
     * Клонирует ветку в фиксированный каталог {@code workspaceDir/cache/{ns}_{repo}__{branch}}.
     * При повторном вызове выполняет {@code git pull} через открытый JGit-хендл.
     */
    @Override
    public Path cloneOrPull(String namespace, String repo, String branch, String token) {
        if (token == null) token = defaultToken;

        String safeBranch = branch.replaceAll("[^a-zA-Z0-9._-]", "_");
        String safeNs    = namespace.replaceAll("[/\\\\:*?\"<>|]", "_");
        Path path = Path.of(workspaceDir, "cache",
                safeNs + "_" + repo + "__" + safeBranch);

        String pathKey = path.toAbsolutePath().toString();

        if (!path.toFile().exists()) {
            log.info("cloneOrPull: cloning '{}/{}' branch='{}' -> {}",
                    namespace, repo, branch, path);
            try {
                Git git = Git.cloneRepository()
                        .setCredentialsProvider(credentials(token))
                        .setURI(projectUrl(namespace, repo))
                        .setDirectory(path.toFile())
                        .setBranch(branch)
                        .setCloneAllBranches(false)
                        .setDepth(1)
                        .call();
                openHandles.put(pathKey, git);
                log.info("cloneOrPull: clone complete -> {}", path);
            } catch (GitAPIException | URISyntaxException e) {
                throw new CodeRepositoryException(
                        "Failed to clone '%s/%s' branch='%s'"
                                .formatted(namespace, repo, branch), e);
            }
        } else {
            log.info("cloneOrPull: pulling '{}/{}' branch='{}'", namespace, repo, branch);
            Git git = openHandles.computeIfAbsent(pathKey, k -> {
                try {
                    return Git.open(path.toFile());
                } catch (IOException ex) {
                    throw new CodeRepositoryException(
                            "Failed to open existing clone at " + path, ex);
                }
            });
            try {
                final String finalToken = token;
                PullResult result = git.pull()
                        .setCredentialsProvider(credentials(finalToken))
                        .call();
                log.info("cloneOrPull: pull success={} {}",
                        result.isSuccessful(), path);
            } catch (GitAPIException e) {
                throw new CodeRepositoryException(
                        "Failed to pull '%s/%s' branch='%s'"
                                .formatted(namespace, repo, branch), e);
            }
        }
        return path;
    }

    // ------------------------------------------------------------------
    // GitLab API
    // ------------------------------------------------------------------

    @Override
    public MergeRequest getMergeRequest(String namespace, String repo,
                                        long mrIid, String token) {
        return gitLabApi(token, api -> {
            try {
                return api.getMergeRequestApi()
                        .getMergeRequest(namespace + "/" + repo, mrIid);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get MR '%d' in '%s/%s'"
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
                        .getMergeRequestChanges(namespace + "/" + repo, mrIid)
                        .getChanges();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get diffs for MR '%d' in '%s/%s'"
                                .formatted(mrIid, namespace, repo), e);
            }
        });
    }

    @Override
    public String getFileContent(String namespace, String repo, String branch,
                                 String filePath, String token) {
        return gitLabApi(token, api -> {
            try (InputStream is = api.getRepositoryFileApi()
                    .getRawFile(namespace + "/" + repo, branch, filePath)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException | GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to read '%s' in '%s/%s' at '%s'"
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
                        .getTree(namespace + "/" + repo, null, branch, true);
                return TreeItem.listFrom(tree);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get tree for '%s/%s' at '%s'"
                                .formatted(namespace, repo, branch), e);
            }
        });
    }

    @Override
    public void cleanup(Path repoDir) {
        try {
            String key = repoDir.toAbsolutePath().toString();
            Git handle = openHandles.remove(key);
            if (handle != null) handle.close();
            deleteDirectory(repoDir.toFile());
        } catch (Exception e) {
            throw new CodeRepositoryException(
                    "Failed to cleanup " + repoDir, e);
        }
    }

    @Override
    public String resolveCommitSha(String namespace, String repo,
                                   String ref, String token) {
        if (ref != null && ref.length() == 40 && ref.matches("[0-9a-fA-F]+"))
            return ref;
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi()
                        .getProject(namespace + "/" + repo);
                return api.getRepositoryApi()
                        .getBranch(project.getId(), ref)
                        .getCommit().getId();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to resolve ref '%s' in '%s/%s'"
                                .formatted(ref, namespace, repo), e);
            }
        });
    }

    @Override
    public List<String> getCommitDiff(String namespace, String repo,
                                      String fromSha, String toSha, String token) {
        return gitLabApi(token, api -> {
            try {
                CompareResults compare = api.getRepositoryApi()
                        .compare(namespace + "/" + repo, fromSha, toSha);
                return compare.getDiffs().stream()
                        .map(Diff::getNewPath)
                        .filter(p -> p != null && p.endsWith(".java"))
                        .distinct()
                        .toList();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get diff '%s'->'%s' in '%s/%s'"
                                .formatted(fromSha, toSha, namespace, repo), e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

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

    private UsernamePasswordCredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("oauth2",
                token == null ? defaultToken : token);
    }

    private String projectUrl(String namespace, String repo) {
        return defaultUrl + "/" + namespace + "/" + repo + ".git";
    }

    private String getClonePath(String namespace, String repo, String branch,
                                String commitId, String token) {
        if (commitId == null)
            commitId = getLastCommit(namespace, repo, branch, token).getId();
        String timestamp = getCommitTimestampDirName(namespace, repo, commitId, token);
        String branchSafe    = branch.replaceAll("[^a-zA-Z0-9._-]", "_");
        String commitSafe    = commitId.substring(0, Math.min(7, commitId.length()));
        String namespaceSafe = namespace.replaceAll("[/\\\\:*?\"<>|]", "_");
        return "%s_%s__%s__%s__%s".formatted(
                namespaceSafe, repo, timestamp, branchSafe, commitSafe);
    }

    private String getCommitTimestampDirName(String namespace, String repo,
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
                        "Failed to get last commit for '%s/%s' branch='%s'"
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
                return api.getCommitsApi()
                        .getCommit(project.getId(), commit);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get commit '%s' in '%s/%s'"
                                .formatted(commit, namespace, repo), e);
            }
        });
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

    public String getProjectUrl(String gitLabHost, String namespace, String repo) {
        return gitLabHost + "/" + namespace + "/" + repo + ".git";
    }
}
