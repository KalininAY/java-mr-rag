package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import org.apache.commons.lang3.Validate;

import java.nio.file.Path;

public abstract class GitLabSourceProvider implements ProjectSourceProvider {
    protected final String owner;
    protected final String repo;
    protected final String branch;
    protected final String commit;
    protected final String token;
    protected final Boolean force;


    public GitLabSourceProvider(RemoteProjectRequest req) {
        Validate.notNull(req, "RemoteProjectRequest is null");
        Validate.notNull(req.owner(), "owner is null");
        Validate.notNull(req.repo(), "repo is null");
        this.owner = req.owner();
        this.repo = req.repo();
        this.branch = req.branch() == null || req.branch().isBlank() ? "main" : req.branch();
        this.commit = req.commit();
        this.token = req.token();
        this.force = req.force();
    }

    /**
     * Returns a stable cache key for this GitLab project + ref combination.
     *
     * <p>Virtual root: {@code /gitlab/<projectId>} (no real directory — just a
     * stable, unique path for use as map key).
     * Fingerprint: {@code "git:<ref>"} when {@code ref} is a 40-char hex SHA,
     * {@code "ref:<ref>"} otherwise (branch or tag — less stable, but usable).
     */
    @Override
    public ProjectKey projectKey() {
        String fingerprint = isFullSha(branch) ? "git:" + branch : "ref:" + branch;
        return new ProjectKey(Path.of("/gitlab/" + owner), fingerprint);
    }


    /**
     * A ref is treated as a full commit SHA when it is exactly 40 hex characters.
     */
    protected static boolean isFullSha(String ref) {
        return ref != null && ref.length() == 40 && ref.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }
}
