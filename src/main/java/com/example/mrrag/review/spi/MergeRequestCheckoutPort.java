package com.example.mrrag.review.spi;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over Git clone + GitLab MR/diff API so {@link com.example.mrrag.review.ReviewService}
 * does not depend on the {@code app} layer.
 */
public interface MergeRequestCheckoutPort {

    MergeRequest getMergeRequest(long projectId, long mrIid) throws GitLabApiException;

    List<Diff> getMrDiffs(long projectId, long mrIid) throws GitLabApiException;

    Path checkoutBranch(long projectId, long mrIid, String branch, String role)
            throws GitLabApiException, GitAPIException, IOException;

    void cleanup(Path repoDir);
}
