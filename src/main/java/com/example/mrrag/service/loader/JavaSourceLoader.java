package com.example.mrrag.service.loader;

import com.example.mrrag.service.loader.VirtualSource;

import java.util.List;

/**
 * Strategy: load Java source files to be processed by Spoon.
 *
 * <p>Two built-in implementations:
 * <ul>
 *   <li>{@link LocalFileSourceLoader}  – scans a locally cloned directory</li>
 *   <li>{@link GitLabSourceLoader}     – fetches files via GitLab API (no clone)</li>
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
