package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.*;

/**
 * Linked node for a LAMBDA element.
 * Renamed from {@code LambdaNodeView}.
 */
public class LinkedLambdaNode extends LinkedNode {

    private LinkedNode                        declaredByExecutable;
    private final List<LinkedNode>            callees          = new ArrayList<>();
    private final List<LinkedNode>            callers          = new ArrayList<>();
    private final List<LinkedClassNode>       instantiates     = new ArrayList<>();
    private final List<LinkedClassNode>       instantiatesAnon = new ArrayList<>();
    private final List<LinkedFieldNode>       readsFields      = new ArrayList<>();
    private final List<LinkedFieldNode>       writesFields     = new ArrayList<>();
    private final List<LinkedVariableNode>    readsLocalVars   = new ArrayList<>();
    private final List<LinkedVariableNode>    writesLocalVars  = new ArrayList<>();
    private final List<LinkedNode>            throwsTypes      = new ArrayList<>();
    private final List<LinkedNode>            referencesTypes  = new ArrayList<>();
    private final Map<String, List<Integer>>  edgeLinesMap     = new HashMap<>();

    public LinkedLambdaNode(GraphNode node) { super(node); }

    public LinkedNode               getDeclaredByExecutable() { return declaredByExecutable; }
    public List<LinkedNode>         getCallees()              { return callees; }
    public List<LinkedNode>         getCallers()              { return callers; }
    public List<LinkedClassNode>    getInstantiates()         { return instantiates; }
    public List<LinkedClassNode>    getInstantiatesAnon()     { return instantiatesAnon; }
    public List<LinkedFieldNode>    getReadsFields()          { return readsFields; }
    public List<LinkedFieldNode>    getWritesFields()         { return writesFields; }
    public List<LinkedVariableNode> getReadsLocalVars()       { return readsLocalVars; }
    public List<LinkedVariableNode> getWritesLocalVars()      { return writesLocalVars; }
    public List<LinkedNode>         getThrowsTypes()          { return throwsTypes; }
    public List<LinkedNode>         getReferencesTypes()      { return referencesTypes; }

    public void addEdgeLine(String targetId, int line) {
        if (line > 0) edgeLinesMap.computeIfAbsent(targetId, k -> new ArrayList<>()).add(line);
    }

    @Override protected List<Integer> edgeLinesTo(String targetId) {
        return edgeLinesMap.getOrDefault(targetId, List.of());
    }

    @Override protected List<LinkedNode> outgoingCallees() { return callees; }

    public void setDeclaredByExecutable(LinkedNode v)    { this.declaredByExecutable = v; }
    public void addCallee(LinkedNode v)                  { callees.add(v); }
    public void addCaller(LinkedNode v)                  { callers.add(v); }
    public void addInstantiates(LinkedClassNode v)       { instantiates.add(v); }
    public void addInstantiatesAnon(LinkedClassNode v)   { instantiatesAnon.add(v); }
    public void addReadsField(LinkedFieldNode v)         { readsFields.add(v); }
    public void addWritesField(LinkedFieldNode v)        { writesFields.add(v); }
    public void addReadsLocalVar(LinkedVariableNode v)   { readsLocalVars.add(v); }
    public void addWritesLocalVar(LinkedVariableNode v)  { writesLocalVars.add(v); }
    public void addThrowsType(LinkedNode v)              { throwsTypes.add(v); }
    public void addReferencesType(LinkedNode v)          { referencesTypes.add(v); }
}
