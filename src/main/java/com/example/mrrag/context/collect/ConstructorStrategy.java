package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.context.plan.LineRangeRequest;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;

/**
 * Context strategy for a changed constructor signature.
 *
 * <p>Collects:
 * <ul>
 *   <li>The full constructor range.</li>
 *   <li>Declaration lines of fields written inside the constructor.</li>
 *   <li>Instantiation call-site lines (reverse INSTANTIATES edges).</li>
 * </ul>
 */
public class ConstructorStrategy implements ContextStrategy {

    private static final int MAX_INSTANTIATION_SITES = 10;

    @Override
    public void collect(GraphNode node, ProjectGraph graph, ContextPlan plan) {
        if (node.kind() != NodeKind.CONSTRUCTOR) return;

        // 1. Full constructor
        plan.add(LineRangeRequest.of(
                node.filePath(), node.startLine(), node.endLine(),
                "constructor: " + node.id()));

        // 2. Fields written (WRITES_FIELD)
        for (GraphEdge edge : graph.edgesFrom(node.id())) {
            if (edge.kind() != EdgeKind.WRITES_FIELD) continue;
            GraphNode field = graph.node(edge.to());
            if (field == null) continue;
            plan.add(LineRangeRequest.of(
                    field.filePath(), field.startLine(), field.startLine(),
                    "writes field: " + field.id()));
        }

        // 3. Instantiation sites (reverse INSTANTIATES)
        int siteCount = 0;
        for (GraphEdge edge : graph.edgesTo(node.id())) {
            if (edge.kind() != EdgeKind.INSTANTIATES) continue;
            if (siteCount++ >= MAX_INSTANTIATION_SITES) break;
            GraphNode caller = graph.node(edge.from());
            if (caller == null) continue;
            plan.add(LineRangeRequest.of(
                    caller.filePath(), caller.startLine(), caller.startLine(),
                    "instantiated by: " + caller.id()));
        }
    }
}
