package com.example.mrrag.context.render;

import java.util.List;

/**
 * Provides raw source lines for a file in the project.
 *
 * <p>Implementations may read from a local filesystem checkout, an in-memory
 * cache, or any other source. Line numbers are 1-based.
 */
public interface FileSource {

    /**
     * Returns all lines of the given file as an unmodifiable list.
     *
     * @param filePath graph-normalised relative file path
     * @return list of lines (1-based: line N is at index N-1), never {@code null}
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    List<String> lines(String filePath);
}
