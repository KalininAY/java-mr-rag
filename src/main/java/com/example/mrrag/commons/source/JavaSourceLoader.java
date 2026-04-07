package com.example.mrrag.commons.source;

import java.util.List;

/**
 * Strategy: load Java source files to be processed by Spoon.
 *
 * <p>Two built-in implementations (in {@code app.source}):
 * <ul>
 *   <li>{@code LocalFileSourceLoader}  – scans a locally cloned directory</li>
 *   <li>{@code GitLabSourceLoader}     – fetches files via GitLab API (no clone)</li>
 * </ul>
 */
public interface JavaSourceLoader {

    /**
     * Load all Java source files and return them as in-memory records.
     *
     * @return non-null, possibly empty list of virtual sources
     * @throws Exception on any IO / API error
     */
    List<VirtualSource> loadSources() throws Exception;
}
