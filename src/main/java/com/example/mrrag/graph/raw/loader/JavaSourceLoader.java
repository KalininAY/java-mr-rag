package com.example.mrrag.graph.raw.loader;

import java.util.List;

/**
 * @deprecated Use {@link com.example.mrrag.commons.source.JavaSourceLoader} instead.
 *             This interface is a deprecated re-export kept for binary compatibility.
 */
@Deprecated
public interface JavaSourceLoader {

    /**
     * Load all Java source files and return them as in-memory records.
     *
     * @return non-null, possibly empty list of virtual sources
     * @throws Exception on any IO / API error
     */
    List<VirtualSource> loadSources() throws Exception;
}
