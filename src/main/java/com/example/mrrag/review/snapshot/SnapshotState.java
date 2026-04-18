package com.example.mrrag.review.snapshot;

/**
 * Describes what data is available in a saved review snapshot.
 * Used by {@link ReviewService} to decide how much work needs to be redone.
 *
 * <pre>
 * GRAPHS_READY   — meta + diffs + source/ + target/ + graph-source.json + graph-target.json
 * SOURCES_READY  — meta + diffs + source/ + target/   (graphs missing or stale)
 * DIFFS_ONLY     — meta + diffs                       (source/target dirs absent)
 * EMPTY          — meta only                          (nothing usable)
 * </pre>
 */
public enum SnapshotState {

    /** Both serialised graphs are present — skip clone + graph build entirely. */
    GRAPHS_READY,

    /** Local project dirs exist but serialised graphs are absent — build graphs, skip clone. */
    SOURCES_READY,

    /** Only diffs.json exists — fetch sources, build graphs. */
    DIFFS_ONLY,

    /** Nothing reusable — start from scratch (clone → diffs → graphs). */
    EMPTY
}
