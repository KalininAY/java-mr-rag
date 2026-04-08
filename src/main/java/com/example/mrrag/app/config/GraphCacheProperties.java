package com.example.mrrag.app.config;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.raw.ClasspathResolver;
import com.example.mrrag.app.source.ProjectKey;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Disk cache for serialized {@link ProjectGraph}
 * and toggles discovery of sibling {@code *-sources.jar} files on the compile classpath.
 */
@Data
@ConfigurationProperties(prefix = "app.graph.cache")
public class GraphCacheProperties {

    /**
     * When true, each {@code ProjectGraph} is written under {@link #dir} and can be
     * loaded individually without rebuilding (same {@link ProjectKey}).
     */
    private boolean serializationEnabled = true;

    /**
     * Root directory for per-project graph cache files (one file per {@code ProjectKey}).
     */
    private String dir = "";

    /**
     * Global directory for shared dependency ({@code dep/...} and {@code jar/...}) segment graphs.
     * Multiple projects that use the same library version will reuse the same file here instead
     * of duplicating it inside every project bundle.
     * <p>
     * When blank (default), the directory is resolved as {@code <dir>/deps} (a sub-directory of
     * the main cache dir).
     */
    private String depsDir = "";

    /**
     * For each compile-scope {@code .jar} on the classpath, if a sibling {@code *-sources.jar}
     * exists, add it as a Spoon input resource so the graph includes dependency sources.
     */
    private boolean sourcesJarsEnabled = true;

    /**
     * When true and GAV can be inferred from a classpath {@code .jar} path, download
     * {@code -sources.jar} from {@link ClasspathResolver.Result#remoteRepositories()}.
     */
    private boolean sourcesRemoteEnabled = true;

    /**
     * Directory for downloaded {@code *-sources.jar} (empty = use {@code app.workspace.dir}/mrrag-sources-cache).
     */
    private String sourcesRemoteCacheDir = "";
}
