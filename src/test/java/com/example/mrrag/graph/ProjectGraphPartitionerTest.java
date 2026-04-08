package com.example.mrrag.graph;

import com.example.mrrag.app.config.GraphCacheProperties;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.raw.GraphSegmentIds;
import com.example.mrrag.graph.raw.ProjectGraphCacheStore;
import com.example.mrrag.graph.raw.ProjectGraphPartitioner;
import com.example.mrrag.app.source.ProjectKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphPartitionerTest {

    @Test
    void mainOnlyWhenNoSourcesJars() {
        var g = new ProjectGraph();
        g.addNode(new GraphNode(
                "com.app.Foo", NodeKind.CLASS, "Foo",
                "src/main/java/com/app/Foo.java", 1, 2, "", "", null));
        Path root = Path.of("/tmp/proj");
        var parts = ProjectGraphPartitioner.partition(g, root, List.of());
        assertThat(parts).containsOnlyKeys(GraphSegmentIds.MAIN);
        assertThat(parts.get(GraphSegmentIds.MAIN).nodes).hasSize(1);
    }

    @Test
    void segmentFileLayout(@TempDir Path cacheDir) throws Exception {
        GraphCacheProperties props = new GraphCacheProperties();
        props.setDir(cacheDir.toString());
        props.setSerializationEnabled(true);
        ProjectGraphCacheStore store = new ProjectGraphCacheStore(props);

        ProjectKey key = new ProjectKey(
                Path.of("/tmp/proj"),
                "test-fingerprint");

        ProjectGraph mainGraph = new ProjectGraph();
        mainGraph.addNode(new GraphNode(
                "com.app.Foo", NodeKind.CLASS, "Foo",
                "src/main/java/com/app/Foo.java", 1, 2, "", "", null));

        Map<String, ProjectGraph> segments = Map.of(
                GraphSegmentIds.MAIN, mainGraph);
        store.savePartitioned(key, segments);

        Path bundle = store.bundleDir(key);
        assertThat(bundle).isDirectory();
        assertThat(bundle.resolve("main.json")).isRegularFile();
        assertThat(bundle.resolve("segments.json")).isRegularFile();

        var loaded = store.tryLoadAllSegments(key);
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).containsOnlyKeys(GraphSegmentIds.MAIN);
        assertThat(loaded.get().get(GraphSegmentIds.MAIN).nodes).hasSize(1);
    }
}
