package com.example.mrrag.review.strategy;

import com.example.mrrag.review.model.EnrichmentSnippet;

import java.util.*;

/**
 * Collects {@link EnrichmentSnippet}s per group while tracking globally
 * seen snippet keys so context can be <em>shared</em> across groups
 * (same declaration relevant to multiple changes) or optionally deduplicated
 * by consumers.
 *
 * <p>Design note: create one {@code ContextCollector} per pipeline run,
 * not per group.
 *
 * <p>Thread-safety: not thread-safe — designed for single-threaded pipeline use.
 */
public class ContextCollector {

    /** Key = filePath + ":" + startLine + "-" + endLine */
    private final Set<String> globalSeenKeys = new LinkedHashSet<>();

    /** Snippets accumulated per groupId */
    private final Map<String, List<EnrichmentSnippet>> byGroup = new LinkedHashMap<>();

    /**
     * Add a snippet to the given group.
     *
     * <p>The snippet is always added to the group's list — context may
     * legitimately appear in multiple groups. The global seen-set tracks
     * first-occurrence for consumers that care.
     */
    public void add(String groupId, EnrichmentSnippet snippet) {
        byGroup.computeIfAbsent(groupId, k -> new ArrayList<>()).add(snippet);
        globalSeenKeys.add(snippetKey(snippet));
    }

    /** Add all snippets from the list to the given group. */
    public void addAll(String groupId, List<EnrichmentSnippet> snippets) {
        snippets.forEach(s -> add(groupId, s));
    }

    /**
     * Returns all snippets collected for the given group.
     * Returns an empty list if no snippets were added for this group.
     */
    public List<EnrichmentSnippet> get(String groupId) {
        return Collections.unmodifiableList(
                byGroup.getOrDefault(groupId, List.of()));
    }

    /**
     * Returns {@code true} if a snippet at the same file+line range was
     * already added by any group in this collector instance.
     */
    public boolean isGloballySeen(EnrichmentSnippet snippet) {
        return globalSeenKeys.contains(snippetKey(snippet));
    }

    /** Total number of snippets across all groups. */
    public int totalSize() {
        return byGroup.values().stream().mapToInt(List::size).sum();
    }

    private static String snippetKey(EnrichmentSnippet s) {
        return s.filePath() + ":" + s.startLine() + "-" + s.endLine();
    }
}
