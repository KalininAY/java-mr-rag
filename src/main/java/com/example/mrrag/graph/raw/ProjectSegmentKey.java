package com.example.mrrag.graph.raw;

import com.example.mrrag.service.GraphSegmentIds;

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
