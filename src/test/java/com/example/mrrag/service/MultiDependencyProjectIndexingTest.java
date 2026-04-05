package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: builds a Spoon AST graph for {@code MultiDependencyProject},
 * checks that project classes are indexed, and verifies that dependency segments
 * (jackson-databind, okhttp, guava) are saved to the global {@code deps/} cache.
 */
@Tag("integration")
class MultiDependencyProjectIndexingTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Returns the classpath root of the MultiDependencyProject fixture.
     * This is a read-only directory inside the test resources jar/directory.
     */
    private static Path fixtureRoot() throws URISyntaxException {
        URL url = MultiDependencyProjectIndexingTest.class
                .getClassLoader()
                .getResource("testProjects/MultiDependencyProject");
        assertThat(url).as("testProjects/MultiDependencyProject must be on test classpath").isNotNull();
        return Path.of(url.toURI());
    }

    /**
     * Copies the fixture into {@code destDir} and then copies the Gradle wrapper
     * ({@code gradlew}, {@code gradlew.bat}, {@code gradle/wrapper/}) from the
     * repository root so that {@link GradleCompileClasspathResolver} can invoke it.
     *
     * <p>The repository root is located by walking up from the compiled test-class
     * output directory until a directory containing {@code gradlew} is found.
     */
    private static Path prepareProjectDir(Path destDir) throws IOException, URISyntaxException {
        // 1. Copy fixture sources into the writable temp dir
        Path fixture = fixtureRoot();
        copyTree(fixture, destDir);

        // 2. Locate repo root (contains gradlew)
        Path repoRoot = findRepoRoot();

        // 3. Copy gradlew / gradlew.bat
        for (String name : new String[]{"gradlew", "gradlew.bat"}) {
            Path src = repoRoot.resolve(name);
            if (Files.exists(src)) {
                Path dst = destDir.resolve(name);
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(dst);
            }
        }

        // 4. Copy gradle/wrapper/
        Path wrapperSrc = repoRoot.resolve("gradle").resolve("wrapper");
        if (Files.isDirectory(wrapperSrc)) {
            Path wrapperDst = destDir.resolve("gradle").resolve("wrapper");
            Files.createDirectories(wrapperDst);
            copyTree(wrapperSrc, wrapperDst);
        }

        return destDir;
    }

    /** Recursively copies {@code src} tree into {@code dst} (dst must exist). */
    private static void copyTree(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(s -> {
                try {
                    Path d = dst.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(d);
                    } else {
                        Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /** Makes a file executable (POSIX only; silently ignored on Windows). */
    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> perms = new java.util.HashSet<>(
                    Files.getPosixFilePermissions(path));
            perms.addAll(EnumSet.of(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows — execute bit is controlled by file extension
        }
    }

    /**
     * Walks up from the test-class output directory until a directory
     * containing {@code gradlew} (or {@code build.gradle}) is found.
     */
    private static Path findRepoRoot() {
        Path start = Path.of(MultiDependencyProjectIndexingTest.class
                .getProtectionDomain().getCodeSource().getLocation().getPath());
        Path candidate = start.toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("gradlew"))
                    || Files.exists(candidate.resolve("build.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate repo root (no gradlew found above " + start + ")");
    }

    private static EdgeKindConfig allEdgesEnabled() {
        return new EdgeKindConfig(new MockEnvironment());
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    /**
     * Verifies that Spoon can index the project source and that the graph
     * contains both project-defined types.
     */
    @Test
    void projectClassesAreIndexed(@TempDir Path tempDir) throws Exception {
        Path projectRoot = prepareProjectDir(tempDir.resolve("project"));
        Files.createDirectories(projectRoot);
        // re-copy fixture only (no gradlew needed for noClasspath mode)
        copyTree(fixtureRoot(), projectRoot);

        AstGraphService service = buildService(tempDir.resolve("cache"));
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        assertThat(graph.nodes).isNotEmpty();
        Set<String> nodeIds = graph.nodes.keySet();
        assertThat(nodeIds).as("UserProfile must be indexed").anyMatch(id -> id.contains("UserProfile"));
        assertThat(nodeIds).as("UserService must be indexed").anyMatch(id -> id.contains("UserService"));
    }

    /**
     * Verifies DECLARES edges: UserService must declare getProfile, evict, cacheSize.
     */
    @Test
    void userServiceDeclaresExpectedMethods(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        copyTree(fixtureRoot(), projectRoot);

        AstGraphService service = buildService(tempDir.resolve("cache"));
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        String userServiceId = graph.nodes.keySet().stream()
                .filter(id -> id.equals("com.example.multi.service.UserService"))
                .findFirst().orElse(null);
        assertThat(userServiceId)
                .as("com.example.multi.service.UserService must be a graph node")
                .isNotNull();

        var declares = graph.outgoing(userServiceId, AstGraphService.EdgeKind.DECLARES);
        var declaredNames = declares.stream().map(e -> e.callee()).toList();
        assertThat(declaredNames).as("must declare getProfile").anyMatch(id -> id.contains("getProfile"));
        assertThat(declaredNames).as("must declare evict").anyMatch(id -> id.contains("evict"));
        assertThat(declaredNames).as("must declare cacheSize").anyMatch(id -> id.contains("cacheSize"));
    }

    /**
     * Verifies that after {@link AstGraphService#buildGraph} the graph cache
     * contains a {@code main} segment for the project and at least one dep
     * segment (jackson-databind) under the global {@code deps/} directory.
     *
     * <p>Gradle wrapper is copied from the repo root at runtime, so this test
     * runs without any {@code assumeThat} guard.
     */
    @Test
    void depSegmentsAreSavedToGlobalDepsDir(@TempDir Path tempDir) throws Exception {
        Path projectRoot = prepareProjectDir(tempDir.resolve("project"));
        Path cacheDir   = tempDir.resolve("cache");

        AstGraphService service = buildService(cacheDir);
        ProjectKey key = service.projectKey(projectRoot);
        service.buildGraph(key);

        ProjectGraphCacheStore store = buildStore(cacheDir);
        var segments = store.tryLoadAllSegments(key);

        assertThat(segments).as("Segment cache must be present").isPresent();
        Map<String, AstGraphService.ProjectGraph> parts = segments.get();

        assertThat(parts).containsKey(GraphSegmentIds.MAIN);

        boolean hasDepSegment = parts.keySet().stream()
                .anyMatch(id -> !id.equals(GraphSegmentIds.MAIN));
        assertThat(hasDepSegment)
                .as("At least one dep segment must be saved. Segments: " + parts.keySet())
                .isTrue();

        assertThat(parts.keySet())
                .as("jackson-databind dep segment must be present")
                .anyMatch(id -> id.contains("jackson-databind"));
    }

    /**
     * Verifies that a second call to {@link AstGraphService#buildGraph} uses
     * the disk cache and does not rebuild.
     */
    @Test
    void secondCallUsesDiskCache(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        copyTree(fixtureRoot(), projectRoot);
        Path cacheDir = tempDir.resolve("cache");

        AstGraphService service = buildService(cacheDir);
        ProjectKey key = service.projectKey(projectRoot);

        AstGraphService.ProjectGraph first = service.buildGraph(key);
        assertThat(first.nodes).isNotEmpty();

        service.invalidate(key);

        AstGraphService.ProjectGraph second = service.buildGraph(key);
        assertThat(second.nodes.keySet())
                .as("Reloaded graph must contain the same nodes")
                .containsAll(first.nodes.keySet());
    }

    // ---------------------------------------------------------------
    // Factory helpers
    // ---------------------------------------------------------------

    private static AstGraphService buildService(Path cacheDir) {
        return new AstGraphService(
                allEdgesEnabled(),
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
        p.setSourcesRemoteEnabled(false);
        return p;
    }
}
