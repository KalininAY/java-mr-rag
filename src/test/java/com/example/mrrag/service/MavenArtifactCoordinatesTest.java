package com.example.mrrag.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenArtifactCoordinatesTest {

    @Test
    void parsesGradleCacheLayout() {
        Path p = Path.of("/home/u/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.36"
                + "/abc123/slf4j-api-1.7.36.jar");
        var g = MavenArtifactCoordinates.tryParseGradleCache(p);
        assertThat(g).isPresent();
        assertThat(g.get().groupId()).isEqualTo("org.slf4j");
        assertThat(g.get().artifactId()).isEqualTo("slf4j-api");
        assertThat(g.get().version()).isEqualTo("1.7.36");
        assertThat(g.get().relativePath()).isEqualTo("org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36-sources.jar");
    }

    @Test
    void parsesMavenLocalLayout() {
        Path p = Path.of("/x/.m2/repository/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar");
        var g = MavenArtifactCoordinates.tryParseMavenLocal(p);
        assertThat(g).isPresent();
        assertThat(g.get().groupId()).isEqualTo("org.apache.commons");
        assertThat(g.get().artifactId()).isEqualTo("commons-lang3");
        assertThat(g.get().version()).isEqualTo("3.12.0");
    }
}
