package com.example.mrrag.graph.cache;

import com.example.mrrag.app.source.ProjectKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of live {@link VersionedGraph}s, one per branch.
 * Thread-safe: backed by {@link ConcurrentHashMap}.
 */
@Slf4j
@Component
public class GraphCache {

    private final ConcurrentMap<ProjectKey, VersionedGraph> cache = new ConcurrentHashMap<>();

    public Optional<VersionedGraph> get(ProjectKey key) {
        return Optional.ofNullable(cache.get(key));
    }

    public void put(ProjectKey key, VersionedGraph vg) {
        cache.put(key, vg);
        log.debug("BranchGraphRegistry: stored {} @ {}", key, vg.commitSha());
    }

    public void invalidate(ProjectKey key) {
        if (cache.remove(key) != null)
            log.info("BranchGraphRegistry: evicted {}", key);
    }

    public boolean contains(ProjectKey key) {
        return cache.containsKey(key);
    }

    public int size() {
        return cache.size();
    }
}
