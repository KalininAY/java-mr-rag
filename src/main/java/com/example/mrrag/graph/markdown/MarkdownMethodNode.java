package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.GraphNode;

import java.util.*;

/**
 * Linked node for a METHOD element.
 * Renamed from {@code MethodNodeView}.
 */
public class MarkdownMethodNode extends MarkdownNode {

    private MarkdownClassNode declaredByClass;
    private final List<MarkdownTypeParamNode>   typeParameters   = new ArrayList<>();
    private final List<MarkdownNode>            callers          = new ArrayList<>();
    private final List<MarkdownNode>            callees          = new ArrayList<>();
    private final List<MarkdownClassNode>       instantiates     = new ArrayList<>();
    private final List<MarkdownClassNode>       instantiatesAnon = new ArrayList<>();
    private final List<MarkdownNode>            referencedMethods = new ArrayList<>();
    private final List<MarkdownFieldNode>       readsFields      = new ArrayList<>();
    private final List<MarkdownFieldNode>       writesFields     = new ArrayList<>();
    private final List<MarkdownVariableNode>    readsLocalVars   = new ArrayList<>();
    private final List<MarkdownVariableNode>    writesLocalVars  = new ArrayList<>();
    private final List<MarkdownNode>            throwsTypes      = new ArrayList<>();
    private final List<MarkdownNode>            referencesTypes  = new ArrayList<>();
    private MarkdownMethodNode overrides;
    private final List<MarkdownMethodNode>      overriddenBy     = new ArrayList<>();
    private final List<MarkdownLambdaNode>      lambdas          = new ArrayList<>();
    private final Map<String, List<Integer>>  edgeLinesMap     = new HashMap<>();

    public MarkdownMethodNode(GraphNode node) { super(node); }

    public MarkdownClassNode getDeclaredByClass()    { return declaredByClass; }
    public List<MarkdownTypeParamNode>   getTypeParameters()     { return typeParameters; }
    public List<MarkdownNode>            getCallers()            { return callers; }
    public List<MarkdownNode>            getCallees()            { return callees; }
    public List<MarkdownClassNode>       getInstantiates()       { return instantiates; }
    public List<MarkdownClassNode>       getInstantiatesAnon()   { return instantiatesAnon; }
    public List<MarkdownNode>            getReferencedMethods()  { return referencedMethods; }
    public List<MarkdownFieldNode>       getReadsFields()        { return readsFields; }
    public List<MarkdownFieldNode>       getWritesFields()       { return writesFields; }
    public List<MarkdownVariableNode>    getReadsLocalVars()     { return readsLocalVars; }
    public List<MarkdownVariableNode>    getWritesLocalVars()    { return writesLocalVars; }
    public List<MarkdownNode>            getThrowsTypes()        { return throwsTypes; }
    public List<MarkdownNode>            getReferencesTypes()    { return referencesTypes; }
    public MarkdownMethodNode getOverrides()          { return overrides; }
    public List<MarkdownMethodNode>      getOverriddenBy()       { return overriddenBy; }
    public List<MarkdownLambdaNode>      getLambdas()            { return lambdas; }

    public void addEdgeLine(String targetId, int line) {
        if (line > 0) edgeLinesMap.computeIfAbsent(targetId, k -> new ArrayList<>()).add(line);
    }

    @Override protected List<Integer> edgeLinesTo(String targetId) {
        return edgeLinesMap.getOrDefault(targetId, List.of());
    }

    @Override protected List<MarkdownNode> outgoingCallees() { return callees; }

    public void setDeclaredByClass(MarkdownClassNode v)     { this.declaredByClass = v; }
    public void addTypeParameter(MarkdownTypeParamNode tp)  { typeParameters.add(tp); }
    public void addCaller(MarkdownNode v)                   { callers.add(v); }
    public void addCallee(MarkdownNode v)                   { callees.add(v); }
    public void addInstantiates(MarkdownClassNode v)        { instantiates.add(v); }
    public void addInstantiatesAnon(MarkdownClassNode v)    { instantiatesAnon.add(v); }
    public void addReferencedMethod(MarkdownNode v)         { referencedMethods.add(v); }
    public void addReadsField(MarkdownFieldNode v)          { readsFields.add(v); }
    public void addWritesField(MarkdownFieldNode v)         { writesFields.add(v); }
    public void addReadsLocalVar(MarkdownVariableNode v)    { readsLocalVars.add(v); }
    public void addWritesLocalVar(MarkdownVariableNode v)   { writesLocalVars.add(v); }
    public void addThrowsType(MarkdownNode v)               { throwsTypes.add(v); }
    public void addReferencesType(MarkdownNode v)           { referencesTypes.add(v); }
    public void setOverrides(MarkdownMethodNode v)          { this.overrides = v; }
    public void addOverriddenBy(MarkdownMethodNode v)       { overriddenBy.add(v); }
    public void addLambda(MarkdownLambdaNode v)             { lambdas.add(v); }
}
