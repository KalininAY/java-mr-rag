package com.example.mrrag.graph;

import com.example.mrrag.graph.raw.MavenArtifactCoordinates;
import com.example.mrrag.graph.raw.RemoteSourcesJarResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteSourcesJarResolverTest {

    @Test
    void cachePathUsesMavenLayout(@TempDir Path cache) {
        var gav = new MavenArtifactCoordinates("org.apache.commons", "commons-lang3", "3.12.0");
        Path p = RemoteSourcesJarResolver.cachePath(cache, gav);
        assertThat(p.getParent().getFileName().toString()).isEqualTo("3.12.0");
        assertThat(p.getFileName().toString()).isEqualTo("commons-lang3-3.12.0-sources.jar");
        assertThat(p.toString().replace('\\', '/')).endsWith(
                "org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0-sources.jar");
    }
}
