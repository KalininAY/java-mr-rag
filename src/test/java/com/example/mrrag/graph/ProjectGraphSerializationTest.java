package com.example.mrrag.graph;

import com.example.mrrag.graph.GraphBuilder.ProjectGraph;
import com.example.mrrag.graph.raw.ProjectGraphSerialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectGraphSerializationTest {

    @Test
    void roundTripJson() throws Exception {
        GraphBuilderImpl.GraphNode n = new GraphBuilderImpl.GraphNode(
                "com.example.Foo",
                GraphBuilderImpl.NodeKind.CLASS,
                "Foo",
                "src/main/java/com/example/Foo.java",
                1, 5,
                "class Foo {}",
                "class Foo {}"
        );
        GraphBuilderImpl.GraphEdge e = new GraphBuilderImpl.GraphEdge(
                "com.example.Foo",
                GraphBuilderImpl.EdgeKind.DECLARES,
                "com.example.Foo#bar()",
                "src/main/java/com/example/Foo.java",
                2
        );
        ProjectGraph g = ProjectGraph.reconstruct(List.of(n), List.of(e));

        byte[] json = ProjectGraphSerialization.toJson(g);
        ProjectGraph g2 = ProjectGraphSerialization.fromJson(json);

        assertEquals(1, g2.nodes.size());
        assertEquals("com.example.Foo", g2.nodes.get("com.example.Foo").id());
        assertEquals(1, g2.outgoing("com.example.Foo").size());
        assertEquals(GraphBuilderImpl.EdgeKind.DECLARES, g2.outgoing("com.example.Foo").get(0).kind());
    }

    @Test
    void roundTripStream() throws Exception {
        ProjectGraph g = ProjectGraph.reconstruct(List.of(), List.of());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ProjectGraphSerialization.write(g, bout);
        ProjectGraph g2 = ProjectGraphSerialization.read(new ByteArrayInputStream(bout.toByteArray()));
        assertTrue(g2.nodes.isEmpty());
    }
}
