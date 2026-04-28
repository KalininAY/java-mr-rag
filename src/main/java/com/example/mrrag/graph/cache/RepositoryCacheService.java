package com.example.mrrag.graph.cache;

import com.example.mrrag.app.repo.CodeRepositoryGateway;
import com.example.mrrag.app.source.ProjectKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages stable local clones of project branches.
 * One live clone per {@link ProjectKey} — no SHA or timestamp in path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryCacheService {

    private final CodeRepositoryGateway gateway;

    private final ConcurrentMap<ProjectKey, BranchCacheEntry> entries =
            new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Resolves the current HEAD SHA for the branch via the GitLab API.
     * Does not touch the local clone.
     */
    public String resolveCurrentSha(ProjectKey key, String token) {
        return gateway.resolveCommitSha(key.namespace(), key.repo(), key.branch(), token);
    }

    /**
     * Clones the branch and records the HEAD SHA.
     * Replaces any existing entry.
     */
    public BranchCacheEntry initCache(ProjectKey key, String token) {
        log.info("RepositoryCacheService.initCache: {}", key);
        Path localPath = gateway.cloneOrPull(key.namespace(), key.repo(), key.branch(), token);
        String sha = gateway.resolveCommitSha(key.namespace(), key.repo(), key.branch(), token);
        BranchCacheEntry entry = new BranchCacheEntry(
                key, localPath, sha, BranchCacheEntry.CacheStatus.READY);
        entries.put(key, entry);
        log.info("RepositoryCacheService.initCache: ready {} @ {}", key, sha);
        return entry;
    }

    /**
     * Executes {@code git pull} on the existing clone.
     * Caller is responsible for checking that a new SHA exists before calling this.
     */
    public BranchCacheEntry pullCache(ProjectKey key, String token) {
        log.info("RepositoryCacheService.pullCache: {}", key);
        Path localPath = gateway.cloneOrPull(key.namespace(), key.repo(), key.branch(), token);
        String sha = gateway.resolveCommitSha(key.namespace(), key.repo(), key.branch(), token);
        BranchCacheEntry entry = new BranchCacheEntry(
                key, localPath, sha, BranchCacheEntry.CacheStatus.READY);
        entries.put(key, entry);
        log.info("RepositoryCacheService.pullCache: updated {} @ {}", key, sha);
        return entry;
    }

    /** Returns changed {@code .java} file paths between two SHAs. */
    public List<String> getChangedFiles(ProjectKey key,
                                        String fromSha, String toSha,
                                        String token) {
        return gateway.getCommitDiff(key.namespace(), key.repo(), fromSha, toSha, token);
    }

    /** Returns the current entry for the branch, if present. */
    public Optional<BranchCacheEntry> get(ProjectKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    /** Removes the in-memory entry (does not delete the clone directory). */
    public void invalidate(ProjectKey key) {
        if (entries.remove(key) != null)
            log.info("RepositoryCacheService.invalidate: evicted {}", key);
    }
}
