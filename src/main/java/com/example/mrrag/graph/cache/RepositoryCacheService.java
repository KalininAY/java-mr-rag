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
 * Manages physical local clones of project branches.
 *
 * <p>Each branch identified by a {@link ProjectKey} gets its own clone directory
 * on disk. This service is responsible for:
 * <ul>
 *   <li>cloning the branch on first access ({@link #initCache});</li>
 *   <li>pulling new commits ({@link #refreshCache});</li>
 *   <li>computing the diff between two commit SHAs ({@link #getChangedFiles}).</li>
 * </ul>
 *
 * <p>The mutable graph state (which commit the graph was built from) is kept
 * separately in {@link BranchGraphRegistry} / {@link VersionedGraph}.
 * This service only tracks the <em>clone</em> state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryCacheService {

    private final CodeRepositoryGateway gateway;

    private final ConcurrentMap<ProjectKey, BranchCacheEntry> entries = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns the existing cache entry for the branch, or clones it
     * from scratch if no entry is present yet.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable — uses default)
     * @return entry with status {@link BranchCacheEntry.CacheStatus#READY}
     */
    public BranchCacheEntry getOrInit(ProjectKey key, String token) {
        return entries.computeIfAbsent(key, k -> initCache(k, token));
    }

    /**
     * Clones the branch and records the resulting HEAD SHA.
     * Replaces any existing entry for the key.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return newly created {@link BranchCacheEntry}
     */
    public BranchCacheEntry initCache(ProjectKey key, String token) {
        log.info("RepositoryCacheService.initCache: cloning {}", key);
        Path localPath = gateway.cloneProject(
                key.namespace(), key.repo(), key.branch(),
                null, false, token);
        String sha = gateway.resolveCommitSha(
                key.namespace(), key.repo(), key.branch(), token);
        BranchCacheEntry entry = new BranchCacheEntry(
                key, localPath, sha, BranchCacheEntry.CacheStatus.READY);
        entries.put(key, entry);
        log.info("RepositoryCacheService.initCache: cloned {} @ {}", key, sha);
        return entry;
    }

    /**
     * Pulls the latest commits for an already-cloned branch.
     *
     * <p>Implemented as a forced re-clone with {@code force=false} so that
     * the gateway reuses the existing directory when possible.
     * After the pull the entry's {@code lastCommitSha} is updated to
     * the new HEAD and status is set back to {@link BranchCacheEntry.CacheStatus#READY}.
     *
     * @param key   branch identifier
     * @param token GitLab personal-access token (nullable)
     * @return updated entry; creates a fresh entry if none existed
     */
    public BranchCacheEntry refreshCache(ProjectKey key, String token) {
        BranchCacheEntry entry = entries.get(key);
        if (entry == null) {
            log.warn("RepositoryCacheService.refreshCache: no entry for {} — falling back to initCache", key);
            return initCache(key, token);
        }
        log.info("RepositoryCacheService.refreshCache: pulling {}", key);
        entry.setStatus(BranchCacheEntry.CacheStatus.UPDATING);
        try {
            gateway.cloneProject(key.namespace(), key.repo(), key.branch(),
                    null, false, token);
            String newSha = gateway.resolveCommitSha(
                    key.namespace(), key.repo(), key.branch(), token);
            entry.setLastCommitSha(newSha);
            entry.setStatus(BranchCacheEntry.CacheStatus.READY);
            log.info("RepositoryCacheService.refreshCache: {} updated to sha={}", key, newSha);
        } catch (Exception ex) {
            entry.setStatus(BranchCacheEntry.CacheStatus.READY); // restore, do not leave UPDATING
            throw ex;
        }
        return entry;
    }

    /**
     * Returns repository-relative paths of {@code .java} files changed
     * between two commit SHAs.
     *
     * @param key     branch identifier
     * @param fromSha older commit SHA (exclusive)
     * @param toSha   newer commit SHA (inclusive)
     * @param token   GitLab personal-access token (nullable)
     * @return list of changed file paths; empty if nothing changed
     */
    public List<String> getChangedFiles(ProjectKey key,
                                        String fromSha, String toSha,
                                        String token) {
        log.debug("RepositoryCacheService.getChangedFiles: {} {}..{}", key, fromSha, toSha);
        return gateway.getCommitDiff(
                key.namespace(), key.repo(), fromSha, toSha, token);
    }

    /**
     * Returns the current entry for the branch, if present.
     */
    public Optional<BranchCacheEntry> get(ProjectKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Removes the cache entry (does not delete the clone directory from disk).
     */
    public void invalidate(ProjectKey key) {
        BranchCacheEntry removed = entries.remove(key);
        if (removed != null) {
            log.info("RepositoryCacheService.invalidate: evicted entry for {}", key);
        }
    }
}
