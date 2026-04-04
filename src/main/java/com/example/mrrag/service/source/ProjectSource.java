package com.example.mrrag.service.source;

/**
 * A single Java source file held in memory.
 *
 * @param path    repository-relative path,
 *                e.g. {@code "src/main/java/com/example/Foo.java"}.
 *                Used verbatim as {@code GraphNode.filePath()} so it must
 *                match the paths that appear in GitLab diff output.
 * @param content raw UTF-8 source text
 */
public record ProjectSource(String path, String content) {}
