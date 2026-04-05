package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: builds a Spoon AST graph for {@code MultiDependencyProject},
 * checks that project classes are indexed, and verifies that dependency segments
 * (jackson-databind, okhttp, guava) are saved to the global {@code deps/} cache.
 *
 * <p>All working directories are placed under {@code build/mr-rag-workspace/}
 * so that artefacts survive the test run and can be inspected manually.
 */
@Tag("integration")
class MultiDependencyProjectIndexingTest {

    // ---------------------------------------------------------------
    // Workspace root  (build/mr-rag-workspace relative to repo root)
    // ---------------------------------------------------------------

    /**
     * Returns {@code <repoRoot>/build/mr-rag-workspace}, creating it if absent.
     * Each test gets a named sub-directory so runs don't stomp on each other.
     */
    private static Path workspaceDir(String testName) throws IOException {
        Path dir = findRepoRoot()
                .resolve("build")
                .resolve("mr-rag-workspace")
                .resolve(testName);
        Files.createDirectories(dir);
        return dir;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static Path fixtureRoot() throws URISyntaxException {
        URL url = MultiDependencyProjectIndexingTest.class
                .getClassLoader()
                .getResource("testProjects/MultiDependencyProject");
        assertThat(url).as("testProjects/MultiDependencyProject must be on test classpath").isNotNull();
        return Path.of(url.toURI());
    }

    /**
     * Copies the fixture into {@code destDir} and injects the Gradle wrapper
     * (gradlew script + gradle/wrapper/gradle-wrapper.jar + gradle-wrapper.properties)
     * so that {@link GradleCompileClasspathResolver} can invoke it.
     *
     * <p>The wrapper JAR is resolved in priority order:
     * <ol>
     *   <li>Repo root {@code gradle/wrapper/gradle-wrapper.jar} (present when commited)</li>
     *   <li>Any {@code gradle-wrapper.jar} found under
     *       {@code ~/.gradle/wrapper/dists/} (downloaded by a previous wrapper run)</li>
     * </ol>
     * If neither source is available the wrapper scripts are still copied, and
     * {@link GradleCompileClasspathResolver} will fall back to the system {@code gradle}
     * executable automatically.
     */
    private static Path prepareProjectDir(Path destDir) throws IOException, URISyntaxException {
        Files.createDirectories(destDir);
        copyTree(fixtureRoot(), destDir);

        Path repoRoot = findRepoRoot();

        // Copy gradlew / gradlew.bat scripts
        for (String name : new String[]{"gradlew", "gradlew.bat"}) {
            Path src = repoRoot.resolve(name);
            if (Files.exists(src)) {
                Path dst = destDir.resolve(name);
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(dst);
            }
        }

        // Prepare gradle/wrapper/ directory
        Path wrapperDst = destDir.resolve("gradle").resolve("wrapper");
        Files.createDirectories(wrapperDst);

        // Copy gradle-wrapper.properties from repo root wrapper dir
        Path wrapperSrc = repoRoot.resolve("gradle").resolve("wrapper");
        if (Files.isDirectory(wrapperSrc)) {
            copyTree(wrapperSrc, wrapperDst);
        }

        // Resolve gradle-wrapper.jar
        Path jarDst = wrapperDst.resolve("gradle-wrapper.jar");
        if (!Files.exists(jarDst)) {
            // 1. Try repo root
            Path jarFromRepo = wrapperSrc.resolve("gradle-wrapper.jar");
            if (Files.exists(jarFromRepo)) {
                Files.copy(jarFromRepo, jarDst, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // 2. Search ~/.gradle/wrapper/dists/ for any gradle-wrapper.jar
                findWrapperJarInGradleHome().ifPresent(found -> {
                    try {
                        Files.copy(found, jarDst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException("Cannot copy gradle-wrapper.jar from " + found, e);
                    }
                });
            }
        }

        return destDir;
    }

    /**
     * Searches {@code ~/.gradle/wrapper/dists} for any {@code gradle-wrapper.jar}
     * cached by a previous Gradle wrapper invocation on this machine.
     */
    private static Optional<Path> findWrapperJarInGradleHome() {
        Path gradleHome = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
        if (!Files.isDirectory(gradleHome)) {
            return Optional.empty();
        }
        try (var walk = Files.walk(gradleHome, 6)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("gradle-wrapper.jar"))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(s -> {
                try {
                    Path d = dst.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) Files.createDirectories(d);
                    else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> perms = new java.util.HashSet<>(
                    Files.getPosixFilePermissions(path));
            perms.addAll(EnumSet.of(
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    private static Path findRepoRoot() {
        Path candidate = Path.of(
                MultiDependencyProjectIndexingTest.class
                        .getProtectionDomain().getCodeSource().getLocation().getPath())
                .toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("gradlew"))
                    || Files.exists(candidate.resolve("build.gradle"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate repo root");
    }

    private static EdgeKindConfig allEdgesEnabled() {
        return new EdgeKindConfig(new MockEnvironment());
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    void projectClassesAreIndexed() throws Exception {
        Path ws          = workspaceDir("projectClassesAreIndexed");
        Path projectRoot = ws.resolve("project");
        Files.createDirectories(projectRoot);
        copyTree(fixtureRoot(), projectRoot);

        AstGraphService service = buildService(ws.resolve("cache"));
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        assertThat(graph.nodes).isNotEmpty();
        Set<String> nodeIds = graph.nodes.keySet();
        assertThat(nodeIds).as("UserProfile must be indexed").anyMatch(id -> id.contains("UserProfile"));
        assertThat(nodeIds).as("UserService must be indexed").anyMatch(id -> id.contains("UserService"));
    }

    @Test
    void userServiceDeclaresExpectedMethods() throws Exception {
        Path ws          = workspaceDir("userServiceDeclaresExpectedMethods");
        Path projectRoot = ws.resolve("project");
        Files.createDirectories(projectRoot);
        copyTree(fixtureRoot(), projectRoot);

        AstGraphService service = buildService(ws.resolve("cache"));
        AstGraphService.ProjectGraph graph = service.buildGraph(projectRoot);

        String userServiceId = graph.nodes.keySet().stream()
                .filter(id -> id.equals("com.example.multi.service.UserService"))
                .findFirst().orElse(null);
        assertThat(userServiceId)
                .as("com.example.multi.service.UserService must be a graph node")
                .isNotNull();

        var declares     = graph.outgoing(userServiceId, AstGraphService.EdgeKind.DECLARES);
        var declaredNames = declares.stream().map(e -> e.callee()).toList();
        assertThat(declaredNames).as("must declare getProfile").anyMatch(id -> id.contains("getProfile"));
        assertThat(declaredNames).as("must declare evict").anyMatch(id -> id.contains("evict"));
        assertThat(declaredNames).as("must declare cacheSize").anyMatch(id -> id.contains("cacheSize"));
    }

    @Test
    void depSegmentsAreSavedToGlobalDepsDir() throws Exception {
        Path ws          = workspaceDir("depSegmentsAreSavedToGlobalDepsDir");
        Path projectRoot = prepareProjectDir(ws.resolve("project"));
        Path cacheDir    = ws.resolve("cache");

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

    @Test
    void secondCallUsesDiskCache() throws Exception {
        Path ws          = workspaceDir("secondCallUsesDiskCache");
        Path projectRoot = ws.resolve("project");
        Files.createDirectories(projectRoot);
        copyTree(fixtureRoot(), projectRoot);
        Path cacheDir = ws.resolve("cache");

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
        p.setSourcesRemoteEnabled(true);
        return p;
    }
}
