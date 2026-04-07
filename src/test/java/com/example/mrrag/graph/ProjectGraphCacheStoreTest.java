package com.example.mrrag.graph;

import com.example.mrrag.app.config.GraphCacheProperties;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.raw.GraphSegmentIds;
import com.example.mrrag.graph.raw.ProjectGraphCacheStore;
import com.example.mrrag.graph.raw.ProjectKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the global-deps-cache layout introduced in feature/global-deps-cache:
 * dep segments live under {@code depsDir/<prefix>/<name>/graph.json + meta.json},
 * main segment stays in the project bundle.
 */
class ProjectGraphCacheStoreTest {

    @TempDir
    Path tmp;

    ProjectGraphCacheStore store;
    ProjectKey key;

    /** A minimal non-empty graph with one node. */
    static ProjectGraph singleNodeGraph(String id) {
        GraphNode n = new GraphNode(
                id, NodeKind.CLASS, "Foo",
                "Foo.java", 1, 5, "class Foo{}", "class Foo{", null);
        return ProjectGraph.reconstruct(List.of(n), List.of());
    }

    @BeforeEach
    void setUp() {
        GraphCacheProperties props = new GraphCacheProperties();
        props.setSerializationEnabled(true);
        props.setDir(tmp.resolve("cache").toString());
        // depsDir intentionally left blank → defaults to cache/deps
        store = new ProjectGraphCacheStore(props);

        // Fake ProjectKey: real path + fixed fingerprint
        key = new ProjectKey(tmp.resolve("project"), "fp-abc123");
    }

    // ------------------------------------------------------------------
    // 1. segmentId format
    // ------------------------------------------------------------------

    @Test
    void segmentIdForGradleJar_isFlat() {
        Path jar = tmp.resolve(".gradle/caches/modules-2/files-2.1/"
                + "org.springframework/spring-core/6.1.0/abc123/spring-core-6.1.0-sources.jar");
        String id = GraphSegmentIds.segmentIdForJar(jar);
        assertEquals("dep/org.springframework_spring-core_6.1.0", id);
        assertFalse(id.contains("/org/"), "groupId dots must NOT be replaced by slashes");
    }

    @Test
    void segmentIdForUnknownJar_isJarPrefix() {
        Path jar = tmp.resolve("some/random/lib-1.0.jar");
        String id = GraphSegmentIds.segmentIdForJar(jar);
        assertTrue(id.startsWith("jar/"), "Unknown jar must use jar/ prefix, got: " + id);
        assertFalse(id.contains("/".repeat(2)), "Should not have double slashes");
    }

    // ------------------------------------------------------------------
    // 2. depSegmentDir path resolution
    // ------------------------------------------------------------------

    @Test
    void depSegmentDir_mavGav_resolvesCorrectly() {
        String segId = "dep/com.example_foo_1.0.0";
        Path dir = store.depSegmentDir(segId);
        Path expected = tmp.resolve("cache").resolve("deps")
                .resolve("dep").resolve("com.example_foo_1.0.0");
        assertEquals(expected, dir);
    }

    @Test
    void depSegmentDir_jarSha_resolvesCorrectly() {
        String sha     = GraphSegmentIds.sha256Hex("/some/path/lib.jar");
        String segId   = "jar/" + sha;
        Path dir       = store.depSegmentDir(segId);
        Path expected  = tmp.resolve("cache").resolve("deps")
                .resolve("jar").resolve(sha);
        assertEquals(expected, dir);
    }

    // ------------------------------------------------------------------
    // 3. save → files on disk
    // ------------------------------------------------------------------

    @Test
    void savePartitioned_createsGraphAndMetaForDep() throws Exception {
        String depId = "dep/com.example_foo_1.0.0";
        Map<String, ProjectGraph> segs = Map.of(
                GraphSegmentIds.MAIN, singleNodeGraph("main.Foo"),
                depId,               singleNodeGraph("dep.Bar")
        );

        store.savePartitioned(key, segs);

        Path depDir = store.depSegmentDir(depId);
        assertTrue(Files.isRegularFile(depDir.resolve("graph.json")),
                "graph.json must exist in dep dir");
        assertTrue(Files.isRegularFile(depDir.resolve("meta.json")),
                "meta.json must exist in dep dir");

        Path bundle = store.bundleDir(key);
        assertTrue(Files.isRegularFile(bundle.resolve("main.json")),
                "main.json must be in project bundle");
        assertTrue(Files.isRegularFile(bundle.resolve("segments.json")),
                "segments.json must be in project bundle");
    }

    @Test
    void savePartitioned_depNotInsideBundleDir() throws Exception {
        String depId = "dep/com.example_foo_1.0.0";
        store.savePartitioned(key, Map.of(depId, singleNodeGraph("dep.Bar")));

        Path bundle = store.bundleDir(key);
        assertFalse(Files.exists(bundle.resolve(depId + ".json")),
                "Dep file must not be duplicated inside project bundle");
        assertFalse(Files.exists(bundle.resolve("dep")),
                "No 'dep' subdirectory should appear inside the project bundle");
    }

    // ------------------------------------------------------------------
    // 4. load round-trip
    // ------------------------------------------------------------------

    @Test
    void saveAndLoad_roundTrip() throws Exception {
        String depId = "dep/org.springframework_spring-core_6.1.0";
        ProjectGraph mainG = singleNodeGraph("main.Foo");
        ProjectGraph depG  = singleNodeGraph("org.springframework.core.SpringVersion");

        store.savePartitioned(key, Map.of(GraphSegmentIds.MAIN, mainG, depId, depG));

        Optional<Map<String, ProjectGraph>> loaded = store.tryLoadAllSegments(key);
        assertTrue(loaded.isPresent());
        assertEquals(2, loaded.get().size());
        assertTrue(loaded.get().get(depId).nodes.containsKey("org.springframework.core.SpringVersion"));
        assertTrue(loaded.get().get(GraphSegmentIds.MAIN).nodes.containsKey("main.Foo"));
    }

    // ------------------------------------------------------------------
    // 5. shared dep — second project reads from global cache
    // ------------------------------------------------------------------

    @Test
    void depSegment_sharedBetweenTwoProjects() throws Exception {
        String depId = "dep/com.example_shared-lib_2.0.0";
        ProjectGraph depG = singleNodeGraph("shared.Util");

        ProjectKey keyA = new ProjectKey(tmp.resolve("projectA"), "fp-aaa");
        store.savePartitioned(keyA, Map.of(depId, depG));

        ProjectKey keyB = new ProjectKey(tmp.resolve("projectB"), "fp-bbb");
        Path bundleB = store.bundleDir(keyB);
        Files.createDirectories(bundleB);
        Files.writeString(bundleB.resolve("segments.json"),
                "{\"segments\":[\"" + depId + "\"]}");

        Optional<Map<String, ProjectGraph>> loaded = store.tryLoadAllSegments(keyB);
        assertTrue(loaded.isPresent(), "Project B must load dep from global cache");
        assertTrue(loaded.get().get(depId).nodes.containsKey("shared.Util"),
                "Node from shared dep must be present");
    }

    // ------------------------------------------------------------------
    // 6. first-writer-wins: existing dep not overwritten
    // ------------------------------------------------------------------

    @Test
    void savePartitioned_existingDepNotOverwritten() throws Exception {
        String depId = "dep/com.example_stable_1.0.0";
        store.savePartitioned(key, Map.of(depId, singleNodeGraph("original.Node")));

        Path graphFile = store.depGraphFile(depId);
        long modifiedBefore = Files.getLastModifiedTime(graphFile).toMillis();

        Thread.sleep(50);
        store.savePartitioned(key, Map.of(depId, singleNodeGraph("different.Node")));

        long modifiedAfter = Files.getLastModifiedTime(graphFile).toMillis();
        assertEquals(modifiedBefore, modifiedAfter, "graph.json must not be overwritten if it already exists");
    }

    // ------------------------------------------------------------------
    // 7. deleteDepSegment removes only the dep dir, not the bundle
    // ------------------------------------------------------------------

    @Test
    void deleteDepSegment_removesDepDirOnly() throws Exception {
        String depId = "dep/com.example_deletable_3.0.0";
        store.savePartitioned(key, Map.of(
                GraphSegmentIds.MAIN, singleNodeGraph("main.Keep"),
                depId,               singleNodeGraph("dep.Remove")));

        store.deleteDepSegment(depId);

        assertFalse(Files.exists(store.depSegmentDir(depId)),
                "Dep segment dir must be deleted");
        assertTrue(Files.isRegularFile(store.bundleDir(key).resolve("main.json")),
                "main.json must remain untouched after dep deletion");
    }

    // ------------------------------------------------------------------
    // 8. delete(key) does not touch global dep files
    // ------------------------------------------------------------------

    @Test
    void deleteBundle_doesNotTouchGlobalDeps() throws Exception {
        String depId = "dep/com.example_keep_1.0.0";
        store.savePartitioned(key, Map.of(
                GraphSegmentIds.MAIN, singleNodeGraph("main.X"),
                depId,               singleNodeGraph("dep.Y")));

        store.delete(key);

        assertFalse(Files.isDirectory(store.bundleDir(key)), "Bundle dir must be gone");
        assertTrue(Files.isRegularFile(store.depGraphFile(depId)),
                "Global dep graph.json must survive bundle deletion");
    }
}
