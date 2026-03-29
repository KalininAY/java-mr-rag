package com.example.mrrag.config;

import org.gitlab4j.api.GitLabApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class AppConfig {

    @Value("${app.gitlab.url}")
    private String gitlabUrl;

    @Value("${app.gitlab.token}")
    private String gitlabToken;

    @Value("${app.workspace.dir}")
    private String workspaceDir;

    @Bean
    public GitLabApi gitLabApi() {
        return new GitLabApi(gitlabUrl, gitlabToken);
    }

    @Bean
    public Path workspacePath() throws IOException {
        Path path = Path.of(workspaceDir);
        Files.createDirectories(path);
        return path;
    }
}
