package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code LAMBDA} node.
 *
 * <p>A lambda is always declared inside a method or constructor.
 * It can also invoke other callables, read/write fields, etc.
 */
public class LambdaNodeView extends GraphNodeView {

    /** Method or constructor that contains this lambda ({@code DECLARES} reverse). */
    private GraphNodeView declaredByExecutable;

    /** Methods / constructors invoked inside this lambda body. */
    private final List<GraphNodeView>    callees         = new ArrayList<>();
    /** Callables that reference this lambda (rare; usually inline). */
    private final List<GraphNodeView>    callers         = new ArrayList<>();
    /** Classes instantiated inside this lambda body. */
    private final List<ClassNodeView>    instantiates     = new ArrayList<>();
    /** Anonymous classes created inside this lambda body. */
    private final List<ClassNodeView>    instantiatesAnon = new ArrayList<>();
    /** Fields read inside this lambda. */
    private final List<FieldNodeView>    readsFields      = new ArrayList<>();
    /** Fields written inside this lambda. */
    private final List<FieldNodeView>    writesFields     = new ArrayList<>();
    /** Local variables / parameters read inside this lambda. */
    private final List<VariableNodeView> readsLocalVars   = new ArrayList<>();
    /** Local variables / parameters written inside this lambda. */
    private final List<VariableNodeView> writesLocalVars  = new ArrayList<>();
    /** Exception types thrown inside this lambda body. */
    private final List<GraphNodeView>    throwsTypes      = new ArrayList<>();
    /** Types referenced as values ({@code Foo.class}, instanceof, cast). */
    private final List<GraphNodeView>    referencesTypes  = new ArrayList<>();

    public LambdaNodeView(GraphNode node) {
        super(node);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public GraphNodeView           getDeclaredByExecutable() { return declaredByExecutable; }
    public List<GraphNodeView>     getCallees()              { return callees; }
    public List<GraphNodeView>     getCallers()              { return callers; }
    public List<ClassNodeView>     getInstantiates()         { return instantiates; }
    public List<ClassNodeView>     getInstantiatesAnon()     { return instantiatesAnon; }
    public List<FieldNodeView>     getReadsFields()          { return readsFields; }
    public List<FieldNodeView>     getWritesFields()         { return writesFields; }
    public List<VariableNodeView>  getReadsLocalVars()       { return readsLocalVars; }
    public List<VariableNodeView>  getWritesLocalVars()      { return writesLocalVars; }
    public List<GraphNodeView>     getThrowsTypes()          { return throwsTypes; }
    public List<GraphNodeView>     getReferencesTypes()      { return referencesTypes; }

    // ── Package-private mutators ──────────────────────────────────────────────
    void setDeclaredByExecutable(GraphNodeView v) { this.declaredByExecutable = v; }
    void addCallee(GraphNodeView v)               { callees.add(v); }
    void addCaller(GraphNodeView v)               { callers.add(v); }
    void addInstantiates(ClassNodeView v)         { instantiates.add(v); }
    void addInstantiatesAnon(ClassNodeView v)     { instantiatesAnon.add(v); }
    void addReadsField(FieldNodeView v)           { readsFields.add(v); }
    void addWritesField(FieldNodeView v)          { writesFields.add(v); }
    void addReadsLocalVar(VariableNodeView v)     { readsLocalVars.add(v); }
    void addWritesLocalVar(VariableNodeView v)    { writesLocalVars.add(v); }
    void addThrowsType(GraphNodeView v)           { throwsTypes.add(v); }
    void addReferencesType(GraphNodeView v)       { referencesTypes.add(v); }
}
