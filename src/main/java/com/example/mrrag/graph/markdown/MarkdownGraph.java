package com.example.mrrag.graph.markdown;

import java.util.*;

/**
 * Container for a fully cross-linked graph of {@link MarkdownNode} instances.
 * Renamed from {@code GraphViewBuilder.ViewGraph}.
 *
 * <p>Provides typed look-up methods by node id or simple name.
 */
public class MarkdownGraph {

    private final Map<String, MarkdownNode>       byId   = new LinkedHashMap<>();
    private final Map<String, List<MarkdownNode>> byName = new LinkedHashMap<>();

    void put(MarkdownNode v) {
        byId.put(v.getId(), v);
        byName.computeIfAbsent(v.getSimpleName(), k -> new ArrayList<>()).add(v);
    }

    /** All nodes, in insertion order. */
    public Collection<MarkdownNode> all() { return Collections.unmodifiableCollection(byId.values()); }

    /** Look up a node by its exact id, or {@code null} if not found. */
    public MarkdownNode byId(String id) { return byId.get(id); }

    /** All nodes whose simple name matches. */
    public List<MarkdownNode> bySimpleName(String name) { return byName.getOrDefault(name, List.of()); }

    public MarkdownClassNode classById(String id) { var v = byId.get(id); return v instanceof MarkdownClassNode c ? c : null; }
    public MarkdownInterfaceNode interfaceById(String id) { var v = byId.get(id); return v instanceof MarkdownInterfaceNode i ? i : null; }
    public MarkdownMethodNode methodById(String id) { var v = byId.get(id); return v instanceof MarkdownMethodNode m ? m : null; }
    public MarkdownConstructorNode constructorById(String id) { var v = byId.get(id); return v instanceof MarkdownConstructorNode c ? c : null; }
    public MarkdownFieldNode fieldById(String id) { var v = byId.get(id); return v instanceof MarkdownFieldNode f ? f : null; }
    public MarkdownVariableNode variableById(String id) { var v = byId.get(id); return v instanceof MarkdownVariableNode vv ? vv : null; }
    public MarkdownLambdaNode lambdaById(String id) { var v = byId.get(id); return v instanceof MarkdownLambdaNode l ? l : null; }

    public List<MarkdownClassNode> allClasses() {
        return byId.values().stream().filter(v -> v instanceof MarkdownClassNode).map(v -> (MarkdownClassNode) v).toList();
    }

    public List<MarkdownInterfaceNode> allInterfaces() {
        return byId.values().stream().filter(v -> v instanceof MarkdownInterfaceNode).map(v -> (MarkdownInterfaceNode) v).toList();
    }

    public List<MarkdownMethodNode> allMethods() {
        return byId.values().stream().filter(v -> v instanceof MarkdownMethodNode).map(v -> (MarkdownMethodNode) v).toList();
    }
}
