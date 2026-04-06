package com.example.mrrag.graph.raw.source;

/**
 * @deprecated Use {@link com.example.mrrag.commons.source.ProjectSource} instead.
 *             This class is a deprecated re-export kept for binary compatibility.
 */
@Deprecated
public record ProjectSource(String path, String content) {
    /** Converts to the canonical commons type. */
    public com.example.mrrag.commons.source.ProjectSource toCommons() {
        return new com.example.mrrag.commons.source.ProjectSource(path, content);
    }

    /** Creates from the canonical commons type. */
    public static ProjectSource from(com.example.mrrag.commons.source.ProjectSource s) {
        return new ProjectSource(s.path(), s.content());
    }
}
