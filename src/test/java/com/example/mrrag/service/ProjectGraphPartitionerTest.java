package com.example.mrrag.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGraphPartitionerTest {

    @Test
    void mainOnlyWhenNoSourcesJars() {
        var g = new AstGraphService.ProjectGraph();
        g.addNode(new AstGraphService.GraphNode(
                "com.app.Foo", AstGraphService.NodeKind.CLASS, "Foo",
                "src/main/java/com/app/Foo.java", 1, 2, "", ""));
        Path root = Path.of("/tmp/proj");
        var parts = ProjectGraphPartitioner.partition(g, root, List.of());
        assertThat(parts).containsOnlyKeys(GraphSegmentIds.MAIN);
        assertThat(parts.get(GraphSegmentIds.MAIN).nodes).hasSize(1);
    }

    @Test
    void segmentFileLayout() {
        Path bundle = Path.of("/cache/bundle");
        assertThat(ProjectGraphCacheStore.segmentFile(bundle, GraphSegmentIds.MAIN))
                .isEqualTo(bundle.resolve("main.json"));
        Path dep = ProjectGraphCacheStore.segmentFile(bundle, "dep/org/foo/lib/1.0");
        assertThat(dep.toString().replace('\\', '/')).endsWith("dep/org/foo/lib/1.0.json");
    }
}
