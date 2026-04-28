package com.example.mrrag.graph.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory registry of live {@link VersionedGraph}s, one per branch.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}.
 * Lifecycle is application-scoped — graphs survive across requests
 * and are updated incrementally via {@link com.example.mrrag.graph.cache.GraphPatcher}.
 */
@Slf4j
@Component
public class BranchGraphRegistry {

    private final ConcurrentMap<BranchKey, VersionedGraph> cache = new ConcurrentHashMap<>();

    /**
     * Returns the cached graph for the given branch, if present.
     */
    public Optional<VersionedGraph> get(BranchKey key) {
        return Optional.ofNullable(cache.get(key));
    }

    /**
     * Stores (or replaces) the graph for the given branch.
     */
    public void put(BranchKey key, VersionedGraph vg) {
        cache.put(key, vg);
        log.debug("BranchGraphRegistry: stored graph for {} @ sha={}", key, vg.commitSha());
    }

    /**
     * Evicts the cached graph for the given branch.
     */
    public void invalidate(BranchKey key) {
        VersionedGraph removed = cache.remove(key);
        if (removed != null) {
            log.info("BranchGraphRegistry: evicted graph for {}", key);
        }
    }

    /**
     * Returns {@code true} if a graph is cached for the given branch.
     */
    public boolean contains(BranchKey key) {
        return cache.containsKey(key);
    }

    /**
     * Number of branches currently cached.
     */
    public int size() {
        return cache.size();
    }
}
