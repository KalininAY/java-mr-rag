package com.example.mrrag.service;

import com.example.mrrag.graph.raw.ProjectGraphSerialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectGraphSerializationTest {

    @Test
    void roundTripJson() throws Exception {
        AstGraphService.GraphNode n = new AstGraphService.GraphNode(
                "com.example.Foo",
                AstGraphService.NodeKind.CLASS,
                "Foo",
                "src/main/java/com/example/Foo.java",
                1, 5,
                "class Foo {}",
                "class Foo {}"
        );
        AstGraphService.GraphEdge e = new AstGraphService.GraphEdge(
                "com.example.Foo",
                AstGraphService.EdgeKind.DECLARES,
                "com.example.Foo#bar()",
                "src/main/java/com/example/Foo.java",
                2
        );
        AstGraphService.ProjectGraph g = AstGraphService.ProjectGraph.reconstruct(List.of(n), List.of(e));

        byte[] json = ProjectGraphSerialization.toJson(g);
        AstGraphService.ProjectGraph g2 = ProjectGraphSerialization.fromJson(json);

        assertEquals(1, g2.nodes.size());
        assertEquals("com.example.Foo", g2.nodes.get("com.example.Foo").id());
        assertEquals(1, g2.outgoing("com.example.Foo").size());
        assertEquals(AstGraphService.EdgeKind.DECLARES, g2.outgoing("com.example.Foo").get(0).kind());
    }

    @Test
    void roundTripStream() throws Exception {
        AstGraphService.ProjectGraph g = AstGraphService.ProjectGraph.reconstruct(List.of(), List.of());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ProjectGraphSerialization.write(g, bout);
        AstGraphService.ProjectGraph g2 = ProjectGraphSerialization.read(new ByteArrayInputStream(bout.toByteArray()));
        assertTrue(g2.nodes.isEmpty());
    }
}
