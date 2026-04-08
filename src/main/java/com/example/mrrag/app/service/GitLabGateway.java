package com.example.mrrag.app.service;

import com.example.mrrag.review.spi.CodeRepositoryGateway;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.Project;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GitLabGateway implements CodeRepositoryGateway {

    private final GitLabApi gitLabApi;

    @Override
    public Path cloneProject(long projectId, String branch, Path path) {
        try {
            Project project = gitLabApi.getProjectApi().getProject(projectId);
            URIish uri = new URIish(project.getSshUrlToRepo());
            Git.cloneRepository()
                    .setURI(uri.toString())
                    .setDirectory(path.toFile())
                    .setBranch(branch)
                    .call();
            return path;
        } catch (GitLabApiException | GitAPIException | URISyntaxException e) {
            throw new CodeRepositoryException("Failed to clone project " + projectId + ", branch " + branch, e);
        }
    }


    @Override
    public void updateRepository(Path repoDir, String branch) {
        try (Git git = Git.open(repoDir.toFile())) {
            git.fetch().call();
            git.pull().setRemoteBranchName(branch).call();
        } catch (GitAPIException | IOException e) {
            throw new CodeRepositoryException("Failed to update repository at " + repoDir, e);
        }
    }

    @Override
    public void checkout(Path repoDir, String branchOrCommit) {
        try (Git git = Git.open(repoDir.toFile())) {
            Repository db = git.getRepository();
            if (branchOrCommit.startsWith("refs/")) {
                git.checkout().setName(branchOrCommit).call();
            } else {
                ObjectId oid = db.resolve(branchOrCommit);
                if (oid == null) {
                    throw new IllegalArgumentException("Unknown ref or commit: " + branchOrCommit);
                }
                git.checkout().setStartPoint(oid.name()).call();
            }
        } catch (GitAPIException | IOException e) {
            throw new CodeRepositoryException("Failed to checkout " + branchOrCommit + " in repo " + repoDir, e);
        }
    }

    @Override
    public MergeRequest getMergeRequest(long projectId, long mrIid) {
        try {
            return gitLabApi.getMergeRequestApi().getMergeRequest(projectId, mrIid);
        } catch (GitLabApiException e) {
            throw new CodeRepositoryException("Failed to get merge request " + mrIid + " in project " + projectId, e);
        }
    }

    @Override
    public List<Diff> getMrDiffs(long projectId, long mrIid) {
        try {
            return gitLabApi.getMergeRequestApi().getDiffs(projectId, mrIid);
        } catch (GitLabApiException e) {
            throw new CodeRepositoryException("Failed to get diffs for merge request " + mrIid + " in project " + projectId, e);
        }
    }

    @Override
    public String getFileContent(long projectId, String ref, String filePath) {
        try (InputStream is = gitLabApi.getRepositoryFileApi()
                .getRawFile(projectId, ref, filePath)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | GitLabApiException e) {
            throw new CodeRepositoryException("Failed to get content file projectID = '%s', ref = '%s', filePath = '%s'".formatted(projectId, ref, filePath), e);
        }
    }

    @Override
    public List<TreeItem> getRepositoryTree(long projectId, String ref) {
        List<org.gitlab4j.api.models.TreeItem> tree;
        try {
            tree = gitLabApi.getRepositoryApi().getTree(projectId, null, ref, true);
        } catch (GitLabApiException e) {
            throw new CodeRepositoryException("Failed get repository tree projectID = '%s', ref = '%s'".formatted(projectId, ref), e);
        }
        return TreeItem.listFrom(tree);
    }

    @Override
    public void cleanup(Path repoDir) {
        try {
            deleteDirectory(repoDir.toFile());
        } catch (Exception e) {
            throw new CodeRepositoryException("Failed to cleanup repository directory " + repoDir, e);
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                if (!file.delete()) {
                    throw new RuntimeException("Failed to delete file: " + file);
                }
            }
        }
        if (!dir.delete()) {
            throw new RuntimeException("Failed to delete directory: " + dir);
        }
    }
}