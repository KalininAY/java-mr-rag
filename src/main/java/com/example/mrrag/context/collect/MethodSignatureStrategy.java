package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.context.plan.LineRangeRequest;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;

/**
 * Context strategy for a changed method signature (parameters or return type).
 *
 * <p>Collects:
 * <ul>
 *   <li>The declaration line of the changed method itself.</li>
 *   <li>The call-site lines of every caller (INVOKES edge pointing <em>to</em> this method).</li>
 *   <li>The declaration of the interface / super-class method that this method overrides, if any.</li>
 * </ul>
 */
public class MethodSignatureStrategy implements ContextStrategy {

    /** Maximum number of caller sites to include (prevents exploding context for hotspot methods). */
    private static final int MAX_CALLERS = 10;

    @Override
    public void collect(GraphNode node, ProjectGraph graph, ContextPlan plan) {
        if (node.kind() != NodeKind.METHOD) return;

        // 1. Declaration line of the changed method
        plan.add(LineRangeRequest.of(
                node.filePath(), node.startLine(), node.startLine(),
                "changed signature: " + node.id()));

        // 2. Callers (reverse INVOKES)
        int callerCount = 0;
        for (GraphEdge edge : graph.edgesTo(node.id())) {
            if (edge.kind() != EdgeKind.INVOKES) continue;
            if (callerCount++ >= MAX_CALLERS) break;

            GraphNode caller = graph.node(edge.from());
            if (caller == null) continue;
            // Include only the call-site line, not the full caller body
            plan.add(LineRangeRequest.of(
                    caller.filePath(), caller.startLine(), caller.startLine(),
                    "caller: " + caller.id()));
        }

        // 3. Overridden method (OVERRIDES edge)
        for (GraphEdge edge : graph.edgesFrom(node.id())) {
            if (edge.kind() != EdgeKind.OVERRIDES) continue;
            GraphNode parent = graph.node(edge.to());
            if (parent == null) continue;
            plan.add(LineRangeRequest.of(
                    parent.filePath(), parent.startLine(), parent.startLine(),
                    "overrides: " + parent.id()));
        }
    }
}
