package com.example.mrrag.graph.cache;

/**
 * Identifies a live graph by GitLab project coordinates + branch name.
 *
 * <p>Used as the key in {@link BranchGraphRegistry}.
 * Unlike {@link com.example.mrrag.app.source.ProjectKey}, this key is
 * independent of any commit SHA — it represents the <em>branch slot</em>
 * that holds the latest built graph.
 */
public record BranchKey(String namespace, String repo, String branch) {

    public BranchKey {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace must not be blank");
        if (repo      == null || repo.isBlank())      throw new IllegalArgumentException("repo must not be blank");
        if (branch    == null || branch.isBlank())    throw new IllegalArgumentException("branch must not be blank");
    }

    @Override
    public String toString() {
        return namespace + "/" + repo + "@" + branch;
    }
}
