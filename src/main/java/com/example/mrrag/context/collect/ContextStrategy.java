package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;

/**
 * Strategy that knows how to collect graph context for one type of node change.
 *
 * <p>Implementations inspect the changed {@link GraphNode}, walk the
 * {@link ProjectGraph} via its edges, and register relevant line ranges
 * into the supplied {@link ContextPlan}.
 *
 * <p>A strategy must be stateless — the same instance may be reused across
 * multiple calls.
 */
public interface ContextStrategy {

    /**
     * Collect context for {@code node} and add the relevant line ranges to {@code plan}.
     *
     * @param node  the changed graph node (from either source or target graph)
     * @param graph the full project graph for the same side (source or target)
     * @param plan  accumulator — add {@link com.example.mrrag.context.plan.LineRangeRequest}s here
     */
    void collect(GraphNode node, ProjectGraph graph, ContextPlan plan);
}
