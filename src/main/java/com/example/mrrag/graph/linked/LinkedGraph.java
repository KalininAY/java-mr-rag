package com.example.mrrag.graph.linked;

import java.util.*;

/**
 * Container for a fully cross-linked graph of {@link LinkedNode} instances.
 * Renamed from {@code GraphViewBuilder.ViewGraph}.
 *
 * <p>Provides typed look-up methods by node id or simple name.
 */
public class LinkedGraph {

    private final Map<String, LinkedNode>       byId   = new LinkedHashMap<>();
    private final Map<String, List<LinkedNode>> byName = new LinkedHashMap<>();

    void put(LinkedNode v) {
        byId.put(v.getId(), v);
        byName.computeIfAbsent(v.getSimpleName(), k -> new ArrayList<>()).add(v);
    }

    /** All nodes, in insertion order. */
    public Collection<LinkedNode> all() { return Collections.unmodifiableCollection(byId.values()); }

    /** Look up a node by its exact id, or {@code null} if not found. */
    public LinkedNode byId(String id) { return byId.get(id); }

    /** All nodes whose simple name matches. */
    public List<LinkedNode> bySimpleName(String name) { return byName.getOrDefault(name, List.of()); }

    public LinkedClassNode       classById(String id) { var v = byId.get(id); return v instanceof LinkedClassNode c ? c : null; }
    public LinkedInterfaceNode   interfaceById(String id) { var v = byId.get(id); return v instanceof LinkedInterfaceNode i ? i : null; }
    public LinkedMethodNode      methodById(String id) { var v = byId.get(id); return v instanceof LinkedMethodNode m ? m : null; }
    public LinkedConstructorNode constructorById(String id) { var v = byId.get(id); return v instanceof LinkedConstructorNode c ? c : null; }
    public LinkedFieldNode       fieldById(String id) { var v = byId.get(id); return v instanceof LinkedFieldNode f ? f : null; }
    public LinkedVariableNode    variableById(String id) { var v = byId.get(id); return v instanceof LinkedVariableNode vv ? vv : null; }
    public LinkedLambdaNode      lambdaById(String id) { var v = byId.get(id); return v instanceof LinkedLambdaNode l ? l : null; }

    public List<LinkedClassNode> allClasses() {
        return byId.values().stream().filter(v -> v instanceof LinkedClassNode).map(v -> (LinkedClassNode) v).toList();
    }

    public List<LinkedInterfaceNode> allInterfaces() {
        return byId.values().stream().filter(v -> v instanceof LinkedInterfaceNode).map(v -> (LinkedInterfaceNode) v).toList();
    }

    public List<LinkedMethodNode> allMethods() {
        return byId.values().stream().filter(v -> v instanceof LinkedMethodNode).map(v -> (LinkedMethodNode) v).toList();
    }
}
