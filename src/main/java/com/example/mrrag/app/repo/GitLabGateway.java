package com.example.mrrag.app.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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

    @Override
    public List<Diff> getMrDiffs(String namespace, String repo,
                                 long mrIid, String token) {

        MergeRequest mr = getMergeRequest(namespace, repo, mrIid, token);
        String effectiveToken = (token != null) ? token : defaultToken;

        List<Diff> changes = fetchChangesWithRawDiffs(namespace, repo, mrIid, effectiveToken);

        List<Diff> result = new ArrayList<>(changes.size());
        for (Diff diff : changes) {
            result.add(enrichEmptyDiff(diff, namespace, repo,
                    mr.getSourceBranch(), mr.getTargetBranch(), token));
        }
        return result;
    }

    /**
     * Запрашивает /changes?access_raw_diffs=true через java.net.http.HttpClient.
     * При ошибке — fallback на стандартный API.
     */
    private List<Diff> fetchChangesWithRawDiffs(String namespace, String repo,
                                                long mrIid, String token) {
        String projectPath = encode(namespace + "/" + repo);
        String url = defaultUrl + "/api/v4/projects/" + projectPath
                + "/merge_requests/" + mrIid + "/changes?access_raw_diffs=true";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("access_raw_diffs returned HTTP {}, falling back", response.statusCode());
                return fallbackChanges(namespace, repo, mrIid);
            }
            Map<String, Object> map = MAPPER.readValue(response.body(), new TypeReference<>() {});
            String changesJson = MAPPER.writeValueAsString(map.get("changes"));
            return MAPPER.readValue(changesJson, new TypeReference<List<Diff>>() {});
        } catch (Exception e) {
            log.warn("access_raw_diffs request failed ({}), falling back to standard API", e.getMessage());
            return fallbackChanges(namespace, repo, mrIid);
        }
    }

    private List<Diff> fallbackChanges(String namespace, String repo, long mrIid) {
        try {
            MergeRequest mr = gitLabApi.getMergeRequestApi()
                    .getMergeRequestChanges(namespace + "/" + repo, mrIid);
            return mr.getChanges();
        } catch (GitLabApiException e) {
            throw new CodeRepositoryException(
                    "Failed to get diffs for MR '%d' in '%s/%s'".formatted(mrIid, namespace, repo), e);
        }
    }

    /**
     * Если diff пустой — синтезирует unified-diff для всех трёх случаев:
     * - новый файл: читается из source, все строки «+»
     * - удалённый файл: читается из target, все строки «-»
     * - изменённый файл: читаются обе версии, будут выведены все строки target «-» и все source «+»
     */
    private Diff enrichEmptyDiff(Diff diff,
                                  String namespace, String repo,
                                  String sourceBranch, String targetBranch,
                                  String token) {
        if (diff.getDiff() != null && !diff.getDiff().isBlank()) {
            return diff;
        }

        boolean isNew     = Boolean.TRUE.equals(diff.getNewFile());
        boolean isDeleted = Boolean.TRUE.equals(diff.getDeletedFile());
        boolean isModified = !isNew && !isDeleted;

        String filePath = isDeleted ? diff.getOldPath() : diff.getNewPath();
        log.warn("Empty diff for '{}' (new={}, deleted={}, modified={}), enriching from file content",
                filePath, isNew, isDeleted, isModified);

        try {
            if (isNew) {
                String content = getFileContent(namespace, repo, sourceBranch, filePath, token);
                diff.setDiff(buildAddedDiff(filePath, content));

            } else if (isDeleted) {
                String oldPath = diff.getOldPath();
                String content = getFileContent(namespace, repo, targetBranch, oldPath, token);
                diff.setDiff(buildDeletedDiff(oldPath, content));

            } else {
                // Изменённый файл — синтезируем diff из двух версий
                String oldContent = getFileContent(namespace, repo, targetBranch, filePath, token);
                String newContent = getFileContent(namespace, repo, sourceBranch, filePath, token);
                diff.setDiff(buildModifiedDiff(filePath, oldContent, newContent));
            }
        } catch (Exception ex) {
            log.warn("Could not enrich diff for '{}': {}", filePath, ex.getMessage());
        }
        return diff;
    }

    /** Все строки файла как «+» (новый файл). */
    private static String buildAddedDiff(String filePath, String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filePath).append("\n");
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) sb.append("+").append(line).append("\n");
        return sb.toString();
    }

    /** Все строки файла как «-» (удалённый файл). */
    private static String buildDeletedDiff(String filePath, String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ /dev/null\n");
        sb.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
        for (String line : lines) sb.append("-").append(line).append("\n");
        return sb.toString();
    }

    /**
     * Синтетический unified-diff для изменённого файла:
     * все строки target — «-», все строки source — «+».
     * Не точный diff, но достаточный для RAG: модель увидит полный контекст изменения.
     */
    private static String buildModifiedDiff(String filePath, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(filePath).append("\n");
        sb.append("+++ b/").append(filePath).append("\n");
        sb.append("@@ -1,").append(oldLines.length)
          .append(" +1,").append(newLines.length).append(" @@\n");
        for (String line : oldLines) sb.append("-").append(line).append("\n");
        for (String line : newLines) sb.append("+").append(line).append("\n");
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

    private static String encode(String projectPath) {
        return projectPath.replace("/", "%2F");
    }

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
