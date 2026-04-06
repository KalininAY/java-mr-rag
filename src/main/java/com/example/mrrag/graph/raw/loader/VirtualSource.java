package com.example.mrrag.graph.raw.loader;

/**
 * @deprecated Use {@link com.example.mrrag.commons.source.VirtualSource} instead.
 *             This class is a deprecated re-export kept for binary compatibility.
 */
@Deprecated
public record VirtualSource(String path, String content) {
    public com.example.mrrag.commons.source.VirtualSource toCommons() {
        return new com.example.mrrag.commons.source.VirtualSource(path, content);
    }

    public static VirtualSource from(com.example.mrrag.commons.source.VirtualSource s) {
        return new VirtualSource(s.path(), s.content());
    }
}
