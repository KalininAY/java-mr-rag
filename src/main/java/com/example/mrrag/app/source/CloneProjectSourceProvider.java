package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.CloneProjectRequest;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;
import java.util.List;

public class CloneProjectSourceProvider extends LocalProjectSourceProvider {
    private final CodeRepositoryGateway repoGateway;
    private final String repoUrl;
    private final String branch;
    private final String commit;
    private final String token;
    private final Boolean force;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    public CloneProjectSourceProvider(CodeRepositoryGateway repoGateway, CloneProjectRequest request) {
        super();
        this.projectRoot = Path.of(workspaceDir);
        this.repoGateway = repoGateway;
        this.repoUrl = request.repoUrl();
        this.branch = request.branch() == null || request.branch().isBlank() ? "main" : request.branch();
        this.commit = request.commit();
        this.token = request.token();
        this.force = request.force();
    }

    @Override
    public List<ProjectSource> getSources() {
        repoGateway.cloneProject(projectRoot, repoUrl, branch, commit, token);
        return super.getSources();
    }
}
