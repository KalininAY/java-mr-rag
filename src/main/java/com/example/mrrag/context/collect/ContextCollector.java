package com.example.mrrag.context.collect;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a changed {@link GraphNode} to the appropriate {@link ContextStrategy}
 * and drives collection into a {@link ContextPlan}.
 *
 * <p>The mapping from {@link NodeKind} to strategy is fixed at construction
 * time. Callers that need a different mapping should supply a custom
 * {@code Map<NodeKind, ContextStrategy>} to the
 * {@link #ContextCollector(Map)} constructor.
 *
 * <p>Nodes whose kind has no registered strategy are silently skipped.
 */
public final class ContextCollector {

    private final Map<NodeKind, ContextStrategy> strategies;

    /** Constructs a collector with the default set of strategies. */
    public ContextCollector() {
        this(defaultStrategies());
    }

    /** Constructs a collector with a caller-supplied strategy map. */
    public ContextCollector(Map<NodeKind, ContextStrategy> strategies) {
        this.strategies = Map.copyOf(strategies);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Collects context for a single changed node.
     *
     * @param node  changed graph node
     * @param graph project graph for the same side (source or target)
     * @param plan  accumulator to add line-range requests into
     */
    public void collect(GraphNode node, ProjectGraph graph, ContextPlan plan) {
        ContextStrategy strategy = strategies.get(node.kind());
        if (strategy != null) {
            strategy.collect(node, graph, plan);
        }
    }

    // ------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------

    private static Map<NodeKind, ContextStrategy> defaultStrategies() {
        Map<NodeKind, ContextStrategy> map = new EnumMap<>(NodeKind.class);
        map.put(NodeKind.METHOD,      new MethodBodyStrategy());
        map.put(NodeKind.CONSTRUCTOR, new ConstructorStrategy());
        map.put(NodeKind.FIELD,       new FieldStrategy());
        return map;
    }
}
