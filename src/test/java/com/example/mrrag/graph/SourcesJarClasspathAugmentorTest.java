package com.example.mrrag.graph;

import com.example.mrrag.graph.raw.SourcesJarClasspathAugmentor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourcesJarClasspathAugmentorTest {

    @Test
    void collectsSiblingSourcesJar(@TempDir Path dir) throws Exception {
        Path lib = dir.resolve("lib");
        Files.createDirectories(lib);
        Path bin = lib.resolve("foo-1.0.jar");
        Path src = lib.resolve("foo-1.0-sources.jar");
        Files.writeString(bin, "x");
        Files.writeString(src, "y");

        String[] cp = {bin.toString(), "/nonexistent/other.jar"};
        var out = SourcesJarClasspathAugmentor.collectSourcesJars(cp);

        assertEquals(1, out.size());
        assertEquals(src.toString(), out.get(0));
    }

    @Test
    void skipsWhenSourcesMissing(@TempDir Path dir) throws Exception {
        Path bin = dir.resolve("orphan.jar");
        Files.writeString(bin, "x");

        assertTrue(SourcesJarClasspathAugmentor.collectSourcesJars(new String[]{bin.toString()}).isEmpty());
    }

    @Test
    void toSourcesJarPath() {
        assertEquals("/a/foo-1.0-sources.jar", SourcesJarClasspathAugmentor.toSourcesJarPath("/a/foo-1.0.jar"));
        assertNull(SourcesJarClasspathAugmentor.toSourcesJarPath("/a/foo.txt"));
    }
}
