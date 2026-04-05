package com.example.mrrag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleRepositoriesParserTest {

    @Test
    void collectsMavenCentralAndCustomUrl(@TempDir Path root) throws Exception {
        Path settings = root.resolve("settings.gradle");
        Files.writeString(settings, """
                pluginManagement {
                    repositories {
                        maven { url 'https://example.com/m2' }
                        mavenCentral()
                    }
                }
                """);

        var repos = GradleRepositoriesParser.collect(root, root);
        assertThat(repos)
                .contains("https://example.com/m2/")
                .contains(GradleRepositoriesParser.MAVEN_CENTRAL);
    }
}
