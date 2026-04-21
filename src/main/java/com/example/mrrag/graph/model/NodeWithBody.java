package com.example.mrrag.graph.model;

public interface NodeWithBody {
    String getBody();
    String getBodyHash();



    // ------------------------------------------------------------------
    // Semantic comparison helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code other} has the same body hash,
     * i.e. the source bodies are identical modulo whitespace.
     */
    default boolean sameBodyAs(NodeWithBody other) {
        return other != null && this.getBodyHash().equals(other.getBodyHash());
    }
}
