package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.graph.cache.GraphCache;

/**
 * Immutable identity key for a specific branch of a GitLab project.
 *
 * <p>Used as the cache key in {@link com.example.mrrag.graph.cache.RepositoryCacheService}
 * and {@link GraphCache}.
 *
 * @param namespace GitLab namespace (group or user)
 * @param repo      repository slug
 * @param branch    branch name
 */
public record ProjectKey(String namespace, String repo, String branch) {

    /**
     * Convenience factory that builds a key from a REST request,
     * defaulting to {@code "master"} when the branch field is blank.
     */
    public static ProjectKey from(RemoteProjectRequest request) {
        String branch = (request.branch() == null || request.branch().isBlank())
                ? "master"
                : request.branch();
        return new ProjectKey(request.namespace(), request.repo(), branch);
    }

    @Override
    public String toString() {
        return namespace + "/" + repo + "@" + branch;
    }
}
