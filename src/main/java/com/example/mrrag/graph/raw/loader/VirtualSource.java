package com.example.mrrag.graph.raw.loader;

/**
 * A single Java source file held in memory.
 *
 * @param path    repository-relative path, e.g. {@code "src/main/java/com/example/Foo.java"}
 * @param content raw UTF-8 source text
 */
public record VirtualSource(String path, String content) {}
