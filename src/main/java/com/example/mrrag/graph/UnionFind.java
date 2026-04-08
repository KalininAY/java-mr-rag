package com.example.mrrag.graph;

import java.util.*;

/**
 * Generic Union-Find (Disjoint Set Union) with path compression and union by rank.
 *
 * <p>All operations are effectively O(α(N)) amortized where α is the inverse
 * Ackermann function — practically constant for any realistic input size.
 *
 * <p>Not thread-safe: intended for single-threaded construction followed by
 * concurrent read-only queries via {@link #component(Object)}.
 *
 * @param <T> element type; must implement {@link Object#equals} and
 *            {@link Object#hashCode} correctly.
 */
public final class UnionFind<T> {

    /** Maps each element to its canonical representative. */
    private final Map<T, T> parent = new HashMap<>();
    /** Maps each representative to its rank (upper bound on subtree height). */
    private final Map<T, Integer> rank = new HashMap<>();

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    /**
     * Adds {@code element} as a singleton component if it is not yet known.
     * Idempotent — safe to call multiple times for the same element.
     */
    public void add(T element) {
        parent.putIfAbsent(element, element);
        rank.putIfAbsent(element, 0);
    }

    /**
     * Merges the components that contain {@code a} and {@code b}.
     * Both elements are {@link #add added} automatically if not yet present.
     */
    public void union(T a, T b) {
        add(a);
        add(b);
        T ra = find(a);
        T rb = find(b);
        if (ra.equals(rb)) return;          // already in the same component

        int rankA = rank.get(ra);
        int rankB = rank.get(rb);
        if (rankA < rankB) {
            parent.put(ra, rb);
        } else if (rankA > rankB) {
            parent.put(rb, ra);
        } else {
            parent.put(rb, ra);
            rank.put(ra, rankA + 1);
        }
    }

    // ------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------

    /**
     * Returns the canonical representative of the component that contains
     * {@code element}, applying path compression along the way.
     *
     * @throws NoSuchElementException if {@code element} has not been added
     */
    public T find(T element) {
        if (!parent.containsKey(element)) {
            throw new NoSuchElementException("UnionFind: unknown element: " + element);
        }
        // Path compression (iterative, avoids stack overflow on deep chains)
        T root = element;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        // Second pass: point every node on the path directly to the root
        T cur = element;
        while (!cur.equals(root)) {
            T next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    /**
     * Returns {@code true} if {@code element} has been added to this structure.
     */
    public boolean contains(T element) {
        return parent.containsKey(element);
    }

    /**
     * Returns {@code true} if {@code a} and {@code b} are in the same component.
     * Both must already be present.
     */
    public boolean connected(T a, T b) {
        return find(a).equals(find(b));
    }

    /**
     * Groups all known elements by their component representative.
     *
     * @return an unmodifiable map from representative → set of members
     */
    public Map<T, Set<T>> components() {
        Map<T, Set<T>> result = new HashMap<>();
        for (T element : parent.keySet()) {
            result.computeIfAbsent(find(element), k -> new LinkedHashSet<>()).add(element);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the component (set of members) that contains {@code element}.
     *
     * @throws NoSuchElementException if {@code element} has not been added
     */
    public Set<T> component(T element) {
        T root = find(element);
        Set<T> result = new LinkedHashSet<>();
        for (T e : parent.keySet()) {
            if (find(e).equals(root)) result.add(e);
        }
        return Collections.unmodifiableSet(result);
    }

    /** Returns the total number of elements across all components. */
    public int size() {
        return parent.size();
    }

    /** Returns the number of distinct components. */
    public int componentCount() {
        long roots = parent.keySet().stream()
                .filter(e -> parent.get(e).equals(e))
                .count();
        return (int) roots;
    }
}
