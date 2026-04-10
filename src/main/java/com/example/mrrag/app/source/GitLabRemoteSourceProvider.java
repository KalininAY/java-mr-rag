package com.example.mrrag.app.source;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.repo.CodeRepositoryException;
import com.example.mrrag.app.repo.TreeItem;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GitLabRemoteSourceProvider extends GitLabSourceProvider {

    private final CodeRepositoryGateway gateway;

    public GitLabRemoteSourceProvider(CodeRepositoryGateway gateway, RemoteProjectRequest req) {
        super(req);
        this.gateway = gateway;
    }

    @Override
    public List<ProjectSource> getSources() {
        log.info("Fetching .java tree from GitLab: namespace {}, repo {}, branch {}", namespace, repo, branch);

        List<TreeItem> tree = gateway.getRepositoryTree(namespace, repo, branch, token);

        List<String> javaPaths = tree.stream()
                .filter(item -> item.type().equals("BLOB"))
                .map(TreeItem::path)
                .filter(p -> p.endsWith(".java"))
                .toList();

        log.info("Found {} .java files in project {}/{} at branch {}",
                javaPaths.size(), namespace, repo, branch);

        List<ProjectSource> sources = new ArrayList<>(javaPaths.size());
        for (String filePath : javaPaths) {
            try {
                String fileContent = gateway.getFileContent(namespace, repo, branch, filePath, token);
                sources.add(new ProjectSource(filePath, fileContent));
            } catch (CodeRepositoryException ex) {
                log.warn("Skipping {} — fetch error: {}", filePath, ex.getMessage());
            }
        }

        log.info("Loaded {}/{} .java files from GitLab (namespace {}, repo {}, branch {})",
                sources.size(), javaPaths.size(), namespace, repo, branch);
        return sources;
    }


}
