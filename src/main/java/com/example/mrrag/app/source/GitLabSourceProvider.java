package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import org.apache.commons.lang3.Validate;

public abstract class GitLabSourceProvider implements ProjectSourceProvider {

    protected final String  namespace;
    protected final String  repo;
    protected final String  branch;
    protected final String  commit;
    protected final String  token;
    protected final Boolean force;

    public GitLabSourceProvider(RemoteProjectRequest req) {
        Validate.notNull(req,            "RemoteProjectRequest is null");
        Validate.notNull(req.namespace(), "namespace is null");
        Validate.notNull(req.repo(),      "repo is null");
        this.namespace = req.namespace();
        this.repo      = req.repo();
        this.branch    = req.branch() == null || req.branch().isBlank() ? "master" : req.branch();
        this.commit    = req.commit();
        this.token     = req.token();
        this.force     = Boolean.TRUE.equals(req.force());
    }

    /**
     * Returns a stable key for this GitLab project + branch combination.
     *
     * <p>Uses {@code namespace/repo@branch} — consistent with
     * {@link ProjectKey#toString()}.
     */
    @Override
    public ProjectKey projectKey() {
        return new ProjectKey(namespace, repo, branch);
    }

    /**
     * A ref is treated as a full commit SHA when it is exactly 40 hex characters.
     */
    protected static boolean isFullSha(String ref) {
        return ref != null && ref.length() == 40 && ref.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }
}
