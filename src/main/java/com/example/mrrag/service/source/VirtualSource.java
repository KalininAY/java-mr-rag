package com.example.mrrag.service.source;

/**
 * An in-memory representation of a single Java source file.
 *
 * @param path    repository-relative path, e.g.
 *                {@code "src/main/java/com/example/Foo.java"}.  Used as the
 *                virtual file name inside Spoon so that
 *                {@link com.example.mrrag.service.AstGraphService.GraphNode#filePath()}
 *                is consistent with GitLab diff paths.
 * @param content raw UTF-8 source text
 */
public record VirtualSource(String path, String content) {}
