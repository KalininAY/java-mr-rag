package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration test: builds a Spoon AST graph for {@code MultiDependencyProject},
 * checks that project classes are indexed, and verifies that dependency segments
 * (jackson-databind, okhttp, guava) are saved to the global {@code deps/} cache.
 *
 * <p>Tagged {@code @Tag("integration")} — skipped unless Gradle wrapper is available
 * in the test project ({@code gradlew} binary must be present).
 */
@Tag("integration")
class MultiDependencyProjectIndexingTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static Path testProjectRoot() throws URISyntaxException {
        URL url = MultiDependencyProjectIndexingTest.class
                .getClassLoader()
                .getResource("testProjects/MultiDependencyProject");
        assertThat(url).as("testProjects/MultiDependencyProject must be on test classpath").isNotNull();
        return Path.of(url.toURI());
    }

    private static boolean gradlewPresent(Path projectRoot) {
        return projectRoot.resolve("gradlew").toFile().canExecute()
                || projectRoot.resolve("gradlew.bat").toFile().exists();
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    /**
     * Verifies that Spoon can index the project source and that the graph
     * contains both project-defined types.
     */
    @Test
    void projectClassesAreIndexed(@TempDir Path cacheDir) throws Exception {
        Path projectRoot = testProjectRoot();

        AstGraphService service = buildService(cacheDir);
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        assertThat(graph.nodes).isNotEmpty();

        Set<String> nodeIds = graph.nodes.keySet();
        assertThat(nodeIds)
                .as("UserProfile class must be indexed")
                .anyMatch(id -> id.contains("UserProfile"));
        assertThat(nodeIds)
                .as("UserService class must be indexed")
                .anyMatch(id -> id.contains("UserService"));
    }

    /**
     * Verifies DECLARES edges: UserService must declare getProfile, evict, cacheSize.
     */
    @Test
    void userServiceDeclaresExpectedMethods(@TempDir Path cacheDir) throws Exception {
        Path projectRoot = testProjectRoot();

        AstGraphService service = buildService(cacheDir);
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        String userServiceId = graph.nodes.keySet().stream()
                .filter(id -> id.equals("com.example.multi.service.UserService"))
                .findFirst()
                .orElse(null);
        assertThat(userServiceId)
                .as("com.example.multi.service.UserService must be a graph node")
                .isNotNull();

        var declares = graph.outgoing(userServiceId, AstGraphService.EdgeKind.DECLARES);
        var declaredNames = declares.stream()
                .map(e -> e.callee())
                .toList();

        assertThat(declaredNames)
                .as("UserService must declare getProfile method")
                .anyMatch(id -> id.contains("getProfile"));
        assertThat(declaredNames)
                .as("UserService must declare evict method")
                .anyMatch(id -> id.contains("evict"));
        assertThat(declaredNames)
                .as("UserService must declare cacheSize method")
                .anyMatch(id -> id.contains("cacheSize"));
    }

    /**
     * Verifies that after {@link AstGraphService#buildGraph} the graph cache
     * contains a {@code main} segment for the project and, when sources-jars
     * are available, at least one dep segment under the global {@code deps/}
     * directory.
     *
     * <p>Skipped when no Gradle wrapper is present (CI without network / wrapper).
     */
    @Test
    void depSegmentsAreSavedToGlobalDepsDir(@TempDir Path cacheDir) throws Exception {
        Path projectRoot = testProjectRoot();
        assumeThat(gradlewPresent(projectRoot))
                .as("Gradle wrapper must be present to resolve dependency classpath")
                .isTrue();

        AstGraphService service = buildService(cacheDir);
        ProjectKey key = service.projectKey(projectRoot);
        service.buildGraph(key);

        ProjectGraphCacheStore store = buildStore(cacheDir);
        var segments = store.tryLoadAllSegments(key);

        assertThat(segments).as("Segment cache must be present").isPresent();
        Map<String, AstGraphService.ProjectGraph> parts = segments.get();

        // main segment must always be present
        assertThat(parts).containsKey(GraphSegmentIds.MAIN);

        // at least one dep segment must exist (jackson, okhttp, or guava)
        boolean hasDepSegment = parts.keySet().stream()
                .anyMatch(id -> !id.equals(GraphSegmentIds.MAIN));
        assertThat(hasDepSegment)
                .as("At least one dependency segment must be saved. Found segments: " + parts.keySet())
                .isTrue();

        // verify known dep segment ids contain expected artifact coordinates
        assertThat(parts.keySet())
                .as("jackson-databind dep segment must be present")
                .anyMatch(id -> id.contains("jackson-databind"));
    }

    /**
     * Verifies that a second call to {@link AstGraphService#buildGraph} uses
     * the disk cache and does not rebuild (no Spoon re-run).
     */
    @Test
    void secondCallUsesDiskCache(@TempDir Path cacheDir) throws Exception {
        Path projectRoot = testProjectRoot();

        AstGraphService service = buildService(cacheDir);
        ProjectKey key = service.projectKey(projectRoot);

        // First call — builds and caches
        AstGraphService.ProjectGraph first = service.buildGraph(key);
        assertThat(first.nodes).isNotEmpty();

        // Invalidate memory cache only (keep disk cache intact)
        service.invalidate(key);

        // Second call — must reload from disk
        AstGraphService.ProjectGraph second = service.buildGraph(key);
        assertThat(second.nodes.keySet())
                .as("Reloaded graph must contain the same nodes as the original")
                .containsAll(first.nodes.keySet());
    }

    // ---------------------------------------------------------------
    // Factory helpers (no Spring context needed)
    // ---------------------------------------------------------------

    private static AstGraphService buildService(Path cacheDir) {
        return new AstGraphService(
                new EdgeKindConfig(),
                cacheProperties(cacheDir),
                buildStore(cacheDir));
    }

    private static ProjectGraphCacheStore buildStore(Path cacheDir) {
        return new ProjectGraphCacheStore(cacheProperties(cacheDir));
    }

    private static GraphCacheProperties cacheProperties(Path cacheDir) {
        GraphCacheProperties p = new GraphCacheProperties();
        p.setDir(cacheDir.toString());
        p.setSerializationEnabled(true);
        p.setSourcesJarsEnabled(true);
        p.setSourcesRemoteEnabled(false); // no network in unit tests
        return p;
    }
}
