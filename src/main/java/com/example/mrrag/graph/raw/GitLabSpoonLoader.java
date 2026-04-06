package com.example.mrrag.graph.raw;

import com.example.mrrag.graph.raw.loader.JavaSourceLoader;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.graph.raw.loader.GitLabSourceLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.springframework.stereotype.Component;
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
 * <p>Source fetching is delegated to {@link GitLabSourceLoader} (implements
 * {@link JavaSourceLoader}), so the two concerns
 * — transport and Spoon model building — are cleanly separated.
 *
 * @deprecated Prefer injecting {@link JavaSourceLoader}
 *     directly and calling {@link AstGraphService#buildGraphFromVirtualSources}.  This class
 *     is kept for backward-compatibility with callers that used the old API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabSpoonLoader {

    private final GitLabApi gitLabApi;

    // ------------------------------------------------------------------
    // Backward-compat alias kept so existing code compiles without changes
    // ------------------------------------------------------------------

    /** @deprecated use {@link VirtualSource} from the {@code loader} package. */
    @Deprecated
    public record VirtualSource(String path, String content) {
        /** Convert to the canonical loader record. */
        public com.example.mrrag.graph.raw.loader.VirtualSource toLoaderRecord() {
            return new com.example.mrrag.graph.raw.loader.VirtualSource(path, content);
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Fetches all {@code .java} files for {@code projectId} at {@code ref}.
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch name, tag, or commit SHA
     */
    public List<VirtualSource> fetchJavaSources(long projectId, String ref)
            throws GitLabApiException, IOException {

        GitLabSourceLoader loader = new GitLabSourceLoader(gitLabApi, projectId, ref);
        return loader.loadSources().stream()
                .map(s -> new VirtualSource(s.path(), s.content()))
                .toList();
    }

    /**
     * Builds a Spoon {@link CtModel} from a pre-fetched list of {@link VirtualSource}s.
     */
    public CtModel buildModel(List<VirtualSource> sources) {
        log.info("Building Spoon model from {} virtual sources", sources.size());

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) { /* older Spoon */ }

        for (VirtualSource src : sources) {
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

    /** Convenience: fetch + build in one call. */
    public CtModel fetchAndBuild(long projectId, String ref)
            throws GitLabApiException, IOException {
        return buildModel(fetchJavaSources(projectId, ref));
    }
}
