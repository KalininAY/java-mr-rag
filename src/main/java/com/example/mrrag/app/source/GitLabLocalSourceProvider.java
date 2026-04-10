package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.repo.CodeRepositoryGateway;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class GitLabLocalSourceProvider extends GitLabSourceProvider {
    private final CodeRepositoryGateway gateway;
    private Path path;

    public GitLabLocalSourceProvider(CodeRepositoryGateway gateway, RemoteProjectRequest req) {
        super(req);
        this.gateway = gateway;
    }

    @Override
    public List<ProjectSource> getSources() {
        path = gateway.cloneProject(owner, repo, branch, commit, token);
        return new LocalProjectSourceProvider(path).getSources();
    }

    @Override
    public Optional<Path> localProjectRoot() {
        return Optional.ofNullable(path);
    }
}
