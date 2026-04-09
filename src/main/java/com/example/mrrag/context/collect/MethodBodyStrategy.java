package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.context.plan.LineRangeRequest;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;

/**
 * Context strategy for a changed method body.
 *
 * <p>Collects:
 * <ul>
 *   <li>The full method range (declaration + body).</li>
 *   <li>Declaration lines of every directly invoked method / constructor.</li>
 *   <li>Declaration lines of every field that is read or written inside the method.</li>
 * </ul>
 */
public class MethodBodyStrategy implements ContextStrategy {

    @Override
    public void collect(GraphNode node, ProjectGraph graph, ContextPlan plan) {
        if (node.kind() != NodeKind.METHOD) return;

        // 1. Full body of the changed method
        plan.add(LineRangeRequest.of(
                node.filePath(), node.startLine(), node.endLine(),
                "method body: " + node.id()));

        // 2. Walk outgoing edges from this method
        for (GraphEdge edge : graph.edgesFrom(node.id())) {
            GraphNode target = graph.node(edge.to());
            if (target == null) continue;

            switch (edge.kind()) {
                case INVOKES, INSTANTIATES -> addDeclaration(target, plan, "invokes");
                case READS_FIELD, WRITES_FIELD -> addDeclaration(target, plan, "field ref");
                default -> { /* not relevant for method-body context */ }
            }
        }
    }

    /** Adds only the declaration line(s) of the target node (not its full body). */
    private static void addDeclaration(GraphNode target, ContextPlan plan, String reason) {
        // declarationSnippet covers exactly one logical line; use startLine only.
        plan.add(LineRangeRequest.of(
                target.filePath(), target.startLine(), target.startLine(),
                reason + ": " + target.id()));
    }
}
