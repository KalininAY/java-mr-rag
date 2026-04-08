package com.example.mrrag.app.source;

import com.example.mrrag.review.spi.CodeRepositoryGateway;

import java.nio.file.Path;
import java.util.List;

public class CloneProjectSourceProvider extends LocalProjectSourceProvider {
    private final CodeRepositoryGateway repoGateway;
    private final long projectId;
    private final String ref;

    public CloneProjectSourceProvider(CodeRepositoryGateway repoGateway, Path projectRoot, long projectId, String ref) {
        super(projectRoot);
        this.repoGateway = repoGateway;
        this.projectId = projectId;
        this.ref = ref;
    }

    @Override
    public List<ProjectSource> getSources() {
        repoGateway.cloneProject(projectId, ref, projectRoot);
        return super.getSources();
    }
}
