package com.example.mrrag.graph;

import com.example.mrrag.app.config.GraphCacheProperties;
import com.example.mrrag.graph.raw.GraphSegmentIds;
import com.example.mrrag.graph.raw.ProjectGraphCacheStore;
import com.example.mrrag.graph.raw.ProjectGraphPartitioner;
import com.example.mrrag.graph.raw.ProjectKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphPartitionerTest {

    @Test
    void mainOnlyWhenNoSourcesJars() {
        var g = new GraphBuilder.ProjectGraph();
        g.addNode(new GraphBuilderImpl.GraphNode(
                "com.app.Foo", GraphBuilderImpl.NodeKind.CLASS, "Foo",
                "src/main/java/com/app/Foo.java", 1, 2, "", ""));
        Path root = Path.of("/tmp/proj");
        var parts = ProjectGraphPartitioner.partition(g, root, List.of());
        assertThat(parts).containsOnlyKeys(GraphSegmentIds.MAIN);
        assertThat(parts.get(GraphSegmentIds.MAIN).nodes).hasSize(1);
    }

    /**
     * Verifies the segment file layout by round-tripping through
     * {@link ProjectGraphCacheStore#savePartitioned} and checking
     * that the expected files are written to disk.
     */
    @Test
    void segmentFileLayout(@TempDir Path cacheDir) throws Exception {
        GraphCacheProperties props = new GraphCacheProperties();
        props.setDir(cacheDir.toString());
        props.setSerializationEnabled(true);
        ProjectGraphCacheStore store = new ProjectGraphCacheStore(props);

        ProjectKey key = new ProjectKey(
                Path.of("/tmp/proj"),
                "test-fingerprint");

        // main segment only
        GraphBuilder.ProjectGraph mainGraph = new GraphBuilder.ProjectGraph();
        mainGraph.addNode(new GraphBuilderImpl.GraphNode(
                "com.app.Foo", GraphBuilderImpl.NodeKind.CLASS, "Foo",
                "src/main/java/com/app/Foo.java", 1, 2, "", ""));

        Map<String, GraphBuilder.ProjectGraph> segments = Map.of(
                GraphSegmentIds.MAIN, mainGraph);
        store.savePartitioned(key, segments);

        // bundle directory must exist
        Path bundle = store.bundleDir(key);
        assertThat(bundle).isDirectory();

        // main.json must be present
        assertThat(bundle.resolve("main.json")).isRegularFile();

        // segments.json manifest must be present
        assertThat(bundle.resolve("segments.json")).isRegularFile();

        // round-trip: reload must return the same single segment
        var loaded = store.tryLoadAllSegments(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).containsOnlyKeys(GraphSegmentIds.MAIN);
        assertThat(loaded.get().get(GraphSegmentIds.MAIN).nodes).hasSize(1);
    }
}
