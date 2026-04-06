package com.example.mrrag.service.source;

import com.example.mrrag.service.dto.ProjectSourceDto;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.TreeItem;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SourcesProvider} that fetches {@code .java} files from the GitLab
 * Repositories API without cloning the project locally.
 *
 * <p>{@link ProjectSourceDto#classpathRoot()} is always {@code null} for
 * API-based projects because there is no local file-system to resolve
 * Gradle/Maven classpath from.
 *
 * <p>This replaces the previous {@link GitLabProjectSourceProvider}
 * as the recommended implementation for GitLab-hosted projects.
 */
@Slf4j
public class GitLabSourcesProvider implements SourcesProvider {

    private final GitLabApi gitLabApi;
    private final long      projectId;
    private final String    ref;

    /**
     * @param gitLabApi authenticated GitLab4J client
     * @param projectId numeric GitLab project ID
     * @param ref       branch, tag, or commit SHA to fetch sources from
     */
    public GitLabSourcesProvider(GitLabApi gitLabApi, long projectId, String ref) {
        this.gitLabApi = gitLabApi;
        this.projectId = projectId;
        this.ref       = ref;
    }

    @Override
    public ProjectSourceDto getProjectSourceDto() throws Exception {
        List<ProjectSource> sources = fetchJavaSources();
        String id = "gitlab:" + projectId + "@" + ref;
        log.info("GitLabSourcesProvider: {} .java files for project {} @ {}", sources.size(), projectId, ref);
        return ProjectSourceDto.ofRemote(id, sources);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<ProjectSource> fetchJavaSources() throws Exception {
        List<ProjectSource> result = new ArrayList<>();
        List<TreeItem> tree = gitLabApi.getRepositoryApi()
                .getTree(projectId, null, ref, true);
        for (TreeItem item : tree) {
            if (item.getType() == TreeItem.Type.BLOB && item.getName().endsWith(".java")) {
                String path = item.getPath();
                try {
                    byte[] raw = gitLabApi.getRepositoryFileApi()
                            .getRawFile(projectId, path, ref);
                    result.add(new ProjectSource(path, new String(raw, java.nio.charset.StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    log.warn("Skipping unreadable file: {} — {}", path, e.getMessage());
                }
            }
        }
        return result;
    }
}
