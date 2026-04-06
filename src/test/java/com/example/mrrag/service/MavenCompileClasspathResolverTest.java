package com.example.mrrag.service;

import com.example.mrrag.graph.raw.MavenCompileClasspathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenCompileClasspathResolverTest {

    @Test
    void findMavenModuleRoot_returnsNearestAncestorWithPom(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("module-a");
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        Path start = module.resolve("src/main/java/org/example");
        Files.createDirectories(start);

        assertThat(MavenCompileClasspathResolver.findMavenModuleRoot(start))
                .contains(module.toAbsolutePath().normalize());
    }

    @Test
    void findMavenModuleRoot_emptyWhenNoPom(@TempDir Path tmp) {
        assertThat(MavenCompileClasspathResolver.findMavenModuleRoot(tmp)).isEmpty();
    }
}
