package com.example.mrrag.service;

import com.example.mrrag.graph.raw.GradleRepositoriesParser;
import com.example.mrrag.graph.raw.MavenRepositoriesParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenRepositoriesParserTest {

    @Test
    void collectsRepositoryUrlFromPom(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <version>1</version>
                  <repositories>
                    <repository>
                      <id>custom</id>
                      <url>https://custom.repo.example/maven/</url>
                    </repository>
                  </repositories>
                </project>
                """);

        var repos = MavenRepositoriesParser.collect(dir);
        assertThat(repos)
                .contains(GradleRepositoriesParser.MAVEN_CENTRAL)
                .contains("https://custom.repo.example/maven/");
    }
}
