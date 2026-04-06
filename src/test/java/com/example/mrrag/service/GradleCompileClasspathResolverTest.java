package com.example.mrrag.service;

import com.example.mrrag.graph.raw.GradleCompileClasspathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleCompileClasspathResolverTest {

    @Test
    void parseClasspathBlock_extractsLineBetweenMarkers() {
        String out = "noise\nSPOON_CP_START\n/a.jar:/b.jar\nSPOON_CP_END\n";
        assertThat(GradleCompileClasspathResolver.parseClasspathBlock(out))
                .contains("/a.jar:/b.jar");
    }

    @Test
    void gradleTaskPath_emptyForRootModule() {
        Path root = Path.of("/repo").toAbsolutePath();
        assertThat(GradleCompileClasspathResolver.gradleTaskPath(root, root)).isEmpty();
    }

    @Test
    void gradleTaskPath_nestedModule() {
        Path root = Path.of("/repo").toAbsolutePath();
        Path mod = Path.of("/repo/services/core").toAbsolutePath();
        assertThat(GradleCompileClasspathResolver.gradleTaskPath(root, mod))
                .isEqualTo(":services:core:mrragSpoonCompileClasspath");
    }
}
