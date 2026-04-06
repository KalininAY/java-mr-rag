package com.example.mrrag.graph.raw.source;

import java.util.List;

/**
 * @deprecated Use {@link com.example.mrrag.commons.source.ProjectSourceProvider} instead.
 *             This interface is a deprecated re-export kept for binary compatibility.
 */
@Deprecated
public interface ProjectSourceProvider {

    /**
     * Load all Java source files and return them as in-memory records.
     *
     * @return non-null list of sources; may be empty
     * @throws Exception on any IO / API error
     */
    List<ProjectSource> getSources() throws Exception;
}
