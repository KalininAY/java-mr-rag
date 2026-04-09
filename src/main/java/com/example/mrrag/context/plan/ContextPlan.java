package com.example.mrrag.context.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates {@link LineRangeRequest}s from multiple {@code ContextStrategy}
 * instances and merges overlapping / adjacent ranges per file.
 *
 * <p>Thread-safety: this class is <em>not</em> thread-safe. Build the plan
 * sequentially and publish the immutable merged result via {@link #merged()}.
 */
public final class ContextPlan {

    /** Insertion-order map so files appear in the order they were first requested. */
    private final Map<String, List<LineRange>> raw = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /** Add a single request. Duplicates and overlaps are resolved lazily in {@link #merged()}. */
    public void add(LineRangeRequest request) {
        raw.computeIfAbsent(request.filePath(), k -> new ArrayList<>())
           .add(request.range());
    }

    /** Convenience overload — skips constructing a {@link LineRangeRequest} when the reason is unneeded. */
    public void add(String filePath, int from, int to) {
        add(LineRangeRequest.of(filePath, from, to, ""));
    }

    /** Returns true when no ranges have been registered. */
    public boolean isEmpty() {
        return raw.isEmpty();
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    /**
     * Returns a new map in which every file's ranges are sorted and merged
     * (overlapping / adjacent ranges are collapsed into one).
     *
     * <p>The returned map and its lists are unmodifiable.
     */
    public Map<String, List<LineRange>> merged() {
        Map<String, List<LineRange>> result = new LinkedHashMap<>();
        raw.forEach((file, ranges) -> result.put(file, mergeRanges(ranges)));
        return Collections.unmodifiableMap(result);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Sorts ranges by start line, then sweeps left-to-right merging
     * overlapping or adjacent intervals. O(n log n).
     */
    static List<LineRange> mergeRanges(List<LineRange> input) {
        if (input.isEmpty()) return List.of();

        List<LineRange> sorted = new ArrayList<>(input);
        sorted.sort((a, b) -> Integer.compare(a.from(), b.from()));

        List<LineRange> merged = new ArrayList<>();
        LineRange current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            LineRange next = sorted.get(i);
            if (current.overlapsOrAdjacent(next)) {
                current = current.mergeWith(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return Collections.unmodifiableList(merged);
    }
}
