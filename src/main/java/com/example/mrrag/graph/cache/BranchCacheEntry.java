package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.ProjectKey;

import java.nio.file.Path;

/**
 * Tracks the state of a locally cloned branch used as the source for graph builds.
 *
 * <p>Instances are stored in {@link RepositoryCacheService} keyed by {@link ProjectKey}.
 * This is a mutable value object — {@link RepositoryCacheService} replaces or updates
 * entries as the clone lifecycle progresses.
 *
 * <h2>Status transitions</h2>
 * <pre>
 *   (absent) ─initCache→ READY
 *   READY    ─refreshCache→ UPDATING ──done→ READY
 *   READY    ─invalidate→  (removed)
 * </pre>
 */
public class BranchCacheEntry {

    public enum CacheStatus { EMPTY, READY, UPDATING }

    private final ProjectKey projectKey;
    private final Path       localPath;
    private volatile String  lastCommitSha;   // null until first clone
    private volatile CacheStatus status;

    public BranchCacheEntry(ProjectKey projectKey, Path localPath,
                            String lastCommitSha, CacheStatus status) {
        this.projectKey    = projectKey;
        this.localPath     = localPath;
        this.lastCommitSha = lastCommitSha;
        this.status        = status;
    }

    // --- accessors ---

    public ProjectKey    projectKey()    { return projectKey; }
    public Path          localPath()     { return localPath; }
    public String        lastCommitSha() { return lastCommitSha; }
    public CacheStatus   status()        { return status; }

    // --- mutators (called only by RepositoryCacheService) ---

    public void setLastCommitSha(String sha)   { this.lastCommitSha = sha; }
    public void setStatus(CacheStatus status)  { this.status = status; }

    @Override
    public String toString() {
        return "BranchCacheEntry{" + projectKey + ", status=" + status
                + ", sha=" + lastCommitSha + ", path=" + localPath + "}";
    }
}
