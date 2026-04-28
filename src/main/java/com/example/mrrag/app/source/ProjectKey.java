package com.example.mrrag.app.source;

/**
 * Stable identity of a project branch used as a cache/registry key.
 *
 * <p>Identifies a branch by its GitLab coordinates: {@code namespace/repo@branch}.
 * Unlike the old {@code (Path, fingerprint)} form, this key is independent of
 * the local filesystem layout and works equally well for local clones and
 * remote (API-only) providers.
 *
 * <p>The mutable state of the graph (which commit was last built) lives in
 * {@link com.example.mrrag.graph.cache.VersionedGraph}, not here.
 */
public record ProjectKey(String namespace, String repo, String branch) {

    public ProjectKey {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        if (repo      == null || repo.isBlank())      throw new IllegalArgumentException("repo must not be blank");
        if (branch    == null || branch.isBlank())    throw new IllegalArgumentException("branch must not be blank");
    }

    @Override
    public String toString() {
        return namespace + "/" + repo + "@" + branch;
    }
}
