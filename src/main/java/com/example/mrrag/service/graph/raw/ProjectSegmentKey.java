package com.example.mrrag.service.graph.raw;

import java.util.Objects;

/**
 * In-memory key for one graph shard ({@link GraphSegmentIds#MAIN} or a dependency segment).
 */
public record ProjectSegmentKey(ProjectKey projectKey, String segmentId) {

    public ProjectSegmentKey {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(segmentId, "segmentId");
    }
}
