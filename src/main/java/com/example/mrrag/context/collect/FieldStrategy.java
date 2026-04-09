package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.context.plan.LineRangeRequest;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;

/**
 * Context strategy for an added, removed, or modified field.
 *
 * <p>Collects:
 * <ul>
 *   <li>The field declaration line itself.</li>
 *   <li>Declaration lines of all constructors of the owning class
 *       (so the reviewer can check initialisation).</li>
 *   <li>Declaration lines of all methods that read or write this field.</li>
 * </ul>
 */
public class FieldStrategy implements ContextStrategy {

    @Override
    public void collect(GraphNode node, ProjectGraph graph, ContextPlan plan) {
        if (node.kind() != NodeKind.FIELD) return;

        // 1. The field declaration itself
        plan.add(LineRangeRequest.of(
                node.filePath(), node.startLine(), node.startLine(),
                "field: " + node.id()));

        // 2. Constructors that write this field (WRITES_FIELD reverse)
        for (GraphEdge edge : graph.edgesTo(node.id())) {
            if (edge.kind() != EdgeKind.WRITES_FIELD) continue;
            GraphNode writer = graph.node(edge.from());
            if (writer == null) continue;
            if (writer.kind() == NodeKind.CONSTRUCTOR) {
                plan.add(LineRangeRequest.of(
                        writer.filePath(), writer.startLine(), writer.startLine(),
                        "constructor writes field: " + writer.id()));
            }
        }

        // 3. Methods that read or write this field
        for (GraphEdge edge : graph.edgesTo(node.id())) {
            if (edge.kind() != EdgeKind.READS_FIELD && edge.kind() != EdgeKind.WRITES_FIELD) continue;
            GraphNode accessor = graph.node(edge.from());
            if (accessor == null || accessor.kind() != NodeKind.METHOD) continue;
            plan.add(LineRangeRequest.of(
                    accessor.filePath(), accessor.startLine(), accessor.startLine(),
                    "accesses field: " + accessor.id()));
        }
    }
}
