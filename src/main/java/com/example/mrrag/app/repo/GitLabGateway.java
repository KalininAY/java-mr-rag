package com.example.mrrag.app.repo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /**
     * Open Git handles for cache directories, keyed by absolute path string.
     */
    private final ConcurrentMap<String, Git> openHandles = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // clone / pull
    // ------------------------------------------------------------------

    @Override
    public Path clone(String namespace, String repo, String branch, String token) {
        if (token == null)
            token = defaultToken;

        Path path = Path.of(workspaceDir, getPath(namespace, repo, branch));

        if (path.toFile().exists()) return pull(namespace, repo, branch, token);

        log.debug("cloneProject: cloning '{}/{}' branch='{}'", namespace, repo, branch);

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
            return path;
        } catch (GitAPIException e) {
            throw new CodeRepositoryException("Failed to clone '%s/%s' branch='%s'".formatted(namespace, repo, branch), e);
        } finally {
            if (git != null) git.close();
        }
    }

    /**
     * Клонирует ветку в фиксированный каталог {@code workspaceDir/cache/{ns}_{repo}__{branch}}.
     * При повторном вызове выполняет {@code git pull} через открытый JGit-хендл.
     */
    @Override
    public Path pull(String namespace, String repo, String branch, String token) {
        if (token == null) token = defaultToken;

        Path path = Path.of(workspaceDir, getPath(namespace, repo, branch));

        if (!path.toFile().exists()) {
            log.warn("Not found path for pull: path = '{}', begin clone", path);
            return clone(namespace, repo, branch, token);
        }

        log.info("Pull: pulling '{}/{}' branch='{}'", namespace, repo, branch);
        try (Git git = Git.open(path.toFile())) {
            git.pull()
                    .setStrategy(MergeStrategy.OURS)
                    .setCredentialsProvider(credentials(token))
                    .call();
            return path;
        } catch (IOException | GitAPIException e) {
            throw new CodeRepositoryException("Failed pull repository '%s/%s' branch='%s'".formatted(namespace, repo, branch), e);
        }
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

    /**
     * Получает diffs Merge Request.
     * <p>
     * GitLab может вернуть пустой {@code diff} для новых ({@code newFile=true})
     * и удалённых ({@code deletedFile=true}) файлов. В этих случаях метод
     * дополнительно загружает содержимое файла и синтезирует unified-diff,
     * чтобы downstream-пайплайн всегда получал непустой diff.
     * <ul>
     *   <li>Новый файл — контент берётся из source-ветки, все строки помечаются «+».</li>
     *   <li>Удалённый файл — контент берётся из target-ветки, все строки помечаются «-».</li>
     * </ul>
     */
    @Override
    public List<Diff> getMrDiffs(String namespace, String repo,
                                 long mrIid, String token) {

        MergeRequest mrWithChanges = gitLabApi(token, api -> {
            try {
                return api.getMergeRequestApi()
                        .getMergeRequestChanges(namespace + "/" + repo, mrIid);
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException(
                        "Failed to get diffs for MR '%d' in '%s/%s'"
                                .formatted(mrIid, namespace, repo), e);
            }
        });

        List<Diff> rawDiffs = mrWithChanges.getChanges();
        String sourceBranch = mrWithChanges.getSourceBranch();
        String targetBranch = mrWithChanges.getTargetBranch();

        List<Diff> enriched = new ArrayList<>(rawDiffs.size());
        for (Diff diff : rawDiffs) {
            boolean emptyDiff = diff.getDiff() == null || diff.getDiff().isBlank();
            if (emptyDiff) {
                if (Boolean.TRUE.equals(diff.getNewFile())) {
                    enriched.add(enrichNewFileDiff(diff, namespace, repo, sourceBranch, token));
                } else if (Boolean.TRUE.equals(diff.getDeletedFile())) {
                    enriched.add(enrichDeletedFileDiff(diff, namespace, repo, targetBranch, token));
                } else {
                    enriched.add(diff);
                }
            } else {
                enriched.add(diff);
            }
        }
        return enriched;
    }

    /**
     * Для нового файла с пустым diff загружает содержимое из source-ветки
     * и синтезирует unified-diff (все строки добавлены).
     */
    private Diff enrichNewFileDiff(Diff diff,
                                   String namespace, String repo,
                                   String sourceBranch, String token) {
        String filePath = diff.getNewPath();
        log.debug("Enriching new-file diff for '{}' in '{}/{}' at '{}'",
                filePath, namespace, repo, sourceBranch);
        try {
            String content = getFileContent(namespace, repo, sourceBranch, filePath, token);
            diff.setDiff(buildAddedFileDiff(filePath, content));
            log.debug("Enriched new-file diff for '{}': {} chars", filePath, diff.getDiff().length());
        } catch (Exception ex) {
            log.warn("Could not enrich diff for new file '{}': {}", filePath, ex.getMessage());
        }
        return diff;
    }

    /**
     * Для удалённого файла с пустым diff загружает содержимое из target-ветки
     * и синтезирует unified-diff (все строки удалены).
     */
    private Diff enrichDeletedFileDiff(Diff diff,
                                       String namespace, String repo,
                                       String targetBranch, String token) {
        String filePath = diff.getOldPath();
        log.debug("Enriching deleted-file diff for '{}' in '{}/{}' at '{}'",
                filePath, namespace, repo, targetBranch);
        try {
            String content = getFileContent(namespace, repo, targetBranch, filePath, token);
            diff.setDiff(buildDeletedFileDiff(filePath, content));
            log.debug("Enriched deleted-file diff for '{}': {} chars", filePath, diff.getDiff().length());
        } catch (Exception ex) {
            log.warn("Could not enrich diff for deleted file '{}': {}", filePath, ex.getMessage());
        }
        return diff;
    }

    /**
     * Строит unified-diff строку для нового файла (все строки — добавленные).
     *
     * <pre>
     * --- /dev/null
     * +++ b/path/to/File.java
     * @@ -0,0 +1,N @@
     * +line1
     * </pre>
     */
    private static String buildAddedFileDiff(String filePath, String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filePath).append("\n");
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Строит unified-diff строку для удалённого файла (все строки — удалённые).
     *
     * <pre>
     * --- a/path/to/File.java
     * +++ /dev/null
     * @@ -1,N +0,0 @@
     * -line1
     * </pre>
     */
    private static String buildDeletedFileDiff(String filePath, String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ /dev/null\n");
        sb.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
        for (String line : lines) {
            sb.append("-").append(line).append("\n");
        }
        return sb.toString();
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
    public String getLastCommit(String namespace, String repo, String branch, String token) {
        return gitLabApi(token, api -> {
            try {
                Project project = api.getProjectApi()
                        .getProject(namespace + "/" + repo);
                return api.getRepositoryApi()
                        .getBranch(project.getId(), branch)
                        .getCommit().getId();
            } catch (GitLabApiException e) {
                throw new CodeRepositoryException("Failed to resolve branch '%s' in '%s/%s'".formatted(branch, namespace, repo), e);
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

    @Override
    public String getPath(String namespace, String repo, String branch) {
        String namespaceSafe = namespace.replaceAll("[/\\\\:*?\"<>|]", "_");
        String branchSafe = branch.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "%s_%s_%s".formatted(namespaceSafe, repo, branchSafe);
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

    private UsernamePasswordCredentialsProvider credentials(String token) {
        return new UsernamePasswordCredentialsProvider("oauth2",
                token == null ? defaultToken : token);
    }

    private String projectUrl(String namespace, String repo) {
        return defaultUrl + "/" + namespace + "/" + repo + ".git";
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
}
