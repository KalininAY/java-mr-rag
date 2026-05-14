package com.example.mrrag;

import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.graph.cache.CachedManagementService;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.ProjectGraph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
@ActiveProfiles("test")
public class EdgeTest {
    @Autowired
    private CachedManagementService cachedService;

    @Test
    void brokenEdges() {
        ProjectGraph graph = cachedService.getOrBuildGraph(new ProjectKey("bugbusters/modules", "extensions", "master"), null);
        Set<Map.Entry<String, List<GraphEdge>>> entries = graph.edgesFrom.entrySet().stream().filter(it -> {
            String callerId = it.getKey();
            if (!graph.nodes.containsKey(callerId)) return true;
            return it.getValue().stream().anyMatch(edge -> !graph.nodes.containsKey(edge.callee()));
        }).collect(Collectors.toSet());

        Assertions.assertTrue(entries.isEmpty(), "Есть битые ребра в графе: " + entries);
    }
}
