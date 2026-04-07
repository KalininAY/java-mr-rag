package com.example.mrrag.service;

import com.example.mrrag.service.source.GitLabProjectSourceProvider;
import com.example.mrrag.service.source.ProjectSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.util.List;

/**
 * Builds a Spoon {@link CtModel} for any GitLab project ref (branch / tag / commit SHA)
 * <strong>without cloning the repository</strong>.
 *
 * <p>Source fetching is delegated to {@link GitLabProjectSourceProvider},
 * so the two concerns — transport and Spoon model building — are cleanly separated.
 *
 * @deprecated Prefer injecting {@link com.example.mrrag.service.source.ProjectSourceProvider}
 *     directly and calling {@link AstGraphService#buildGraph}. This class
 *     is kept for backward-compatibility with callers that used the old API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabSpoonLoader {

    private final GitLabApi gitLabApi;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Fetches all {@code .java} files for {@code projectId} at {@code ref}.
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch name, tag, or commit SHA
     * @return list of in-memory sources
     * @deprecated use {@link GitLabProjectSourceProvider#getSources()} directly
     */
    @Deprecated
    public List<ProjectSource> fetchJavaSources(long projectId, String ref)
            throws GitLabApiException, IOException {
        return new GitLabProjectSourceProvider(gitLabApi, projectId, ref).getSources();
    }

    /**
     * Builds a Spoon {@link CtModel} from a pre-fetched list of {@link ProjectSource}s.
     *
     * @deprecated use {@link AstGraphService#buildGraph} instead
     */
    @Deprecated
    public CtModel buildModel(List<ProjectSource> sources) {
        log.info("Building Spoon model from {} virtual sources", sources.size());

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) { /* older Spoon */ }

        for (ProjectSource src : sources) {
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        try {
            return launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException — returning partial model: {}", mbe.getMessage());
            CtModel partial = launcher.getModel();
            if (partial == null) {
                log.error("Spoon returned null model — empty fallback");
                return new Launcher().buildModel();
            }
            return partial;
        }
    }

    /**
     * Convenience: fetch + build in one call.
     *
     * @deprecated use {@link AstGraphService#buildGraph} instead
     */
    @Deprecated
    public CtModel fetchAndBuild(long projectId, String ref)
            throws GitLabApiException, IOException {
        return buildModel(fetchJavaSources(projectId, ref));
    }
}
