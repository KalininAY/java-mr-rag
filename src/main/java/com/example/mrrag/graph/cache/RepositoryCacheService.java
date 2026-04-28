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
 * Manages stable local clones of project branches via
 * {@link CodeRepositoryGateway#cloneOrPull}.
 *
 * <p>One live clone per {@link ProjectKey} — no SHA or timestamp in path.
 * The clone is reused across requests; new commits are pulled in-place.
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
     * Returns the existing entry or clones the branch on first call.
     */
    public BranchCacheEntry getOrInit(ProjectKey key, String token) {
        return entries.computeIfAbsent(key, k -> initCache(k, token));
    }

    /**
     * Clones the branch via {@link CodeRepositoryGateway#cloneOrPull} and
     * records the HEAD SHA. Replaces any existing entry.
     */
    public BranchCacheEntry initCache(ProjectKey key, String token) {
        log.info("RepositoryCacheService.initCache: {}", key);
        Path localPath = gateway.cloneOrPull(
                key.namespace(), key.repo(), key.branch(), token);
        String sha = gateway.resolveCommitSha(
                key.namespace(), key.repo(), key.branch(), token);
        BranchCacheEntry entry = new BranchCacheEntry(
                key, localPath, sha, BranchCacheEntry.CacheStatus.READY);
        entries.put(key, entry);
        log.info("RepositoryCacheService.initCache: ready {} @ {}", key, sha);
        return entry;
    }

    /**
     * Pulls latest commits via {@link CodeRepositoryGateway#cloneOrPull}.
     *
     * <p>If the HEAD SHA has not changed, returns the existing entry unchanged.
     * If a new SHA is detected, creates a fresh {@link BranchCacheEntry} with
     * the same {@code localPath} (pull updated it in-place) and the new SHA.
     */
    public BranchCacheEntry refreshCache(ProjectKey key, String token) {
        BranchCacheEntry current = entries.get(key);
        if (current == null) {
            log.warn("RepositoryCacheService.refreshCache: no entry for {} — init", key);
            return initCache(key, token);
        }

        // Resolve new SHA before pull to decide whether work is needed
        String newSha = gateway.resolveCommitSha(
                key.namespace(), key.repo(), key.branch(), token);

        if (newSha.equals(current.lastCommitSha())) {
            log.debug("RepositoryCacheService.refreshCache: {} already up-to-date @ {}",
                    key, newSha);
            return current;
        }

        log.info("RepositoryCacheService.refreshCache: pulling {} {} -> {}",
                key, current.lastCommitSha(), newSha);
        current.setStatus(BranchCacheEntry.CacheStatus.UPDATING);
        try {
            Path localPath = gateway.cloneOrPull(
                    key.namespace(), key.repo(), key.branch(), token);
            BranchCacheEntry updated = new BranchCacheEntry(
                    key, localPath, newSha, BranchCacheEntry.CacheStatus.READY);
            entries.put(key, updated);
            log.info("RepositoryCacheService.refreshCache: {} updated to {}", key, newSha);
            return updated;
        } catch (Exception ex) {
            current.setStatus(BranchCacheEntry.CacheStatus.READY);
            throw ex;
        }
    }

    /**
     * Returns changed {@code .java} file paths between two SHAs.
     */
    public List<String> getChangedFiles(ProjectKey key,
                                        String fromSha, String toSha,
                                        String token) {
        return gateway.getCommitDiff(
                key.namespace(), key.repo(), fromSha, toSha, token);
    }

    /** Returns the current entry for the branch, if present. */
    public Optional<BranchCacheEntry> get(ProjectKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    /** Removes the in-memory entry (does not delete the clone directory). */
    public void invalidate(ProjectKey key) {
        BranchCacheEntry removed = entries.remove(key);
        if (removed != null)
            log.info("RepositoryCacheService.invalidate: evicted {}", key);
    }
}
