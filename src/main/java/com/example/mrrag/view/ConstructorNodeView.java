package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code CONSTRUCTOR} node.
 *
 * <p>Mirrors {@link MethodNodeView} but without overrides / return-type concepts.
 */
public class ConstructorNodeView extends GraphNodeView {

    /** Class that declares this constructor. */
    private ClassNodeView declaredByClass;

    /** Callables that invoke this constructor (via {@code new} or {@code this}/{@code super}). */
    private final List<GraphNodeView> callers = new ArrayList<>();
    /** Methods or constructors invoked inside this constructor body. */
    private final List<GraphNodeView> callees = new ArrayList<>();

    /** Classes instantiated via {@code new Foo()} inside this constructor body. */
    private final List<ClassNodeView>    instantiates     = new ArrayList<>();
    /** Anonymous classes created inside this constructor body. */
    private final List<ClassNodeView>    instantiatesAnon = new ArrayList<>();

    /** Fields read by this constructor. */
    private final List<FieldNodeView>    readsFields     = new ArrayList<>();
    /** Fields written by this constructor. */
    private final List<FieldNodeView>    writesFields    = new ArrayList<>();

    /** Local variables / parameters read by this constructor. */
    private final List<VariableNodeView> readsLocalVars  = new ArrayList<>();
    /** Local variables / parameters written by this constructor. */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    /** Exception types thrown inside this constructor body. */
    private final List<GraphNodeView> throwsTypes     = new ArrayList<>();
    /** Types referenced as values ({@code Foo.class}, instanceof, cast). */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();
    /** Annotation types applied to this constructor. */
    private final List<GraphNodeView> annotations     = new ArrayList<>();
    /** Lambda expressions declared in this constructor body. */
    private final List<LambdaNodeView> lambdas        = new ArrayList<>();

    public ConstructorNodeView(GraphNode node) {
        super(node);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public ClassNodeView           getDeclaredByClass()  { return declaredByClass; }
    public List<GraphNodeView>     getCallers()          { return callers; }
    public List<GraphNodeView>     getCallees()          { return callees; }
    public List<ClassNodeView>     getInstantiates()     { return instantiates; }
    public List<ClassNodeView>     getInstantiatesAnon() { return instantiatesAnon; }
    public List<FieldNodeView>     getReadsFields()      { return readsFields; }
    public List<FieldNodeView>     getWritesFields()     { return writesFields; }
    public List<VariableNodeView>  getReadsLocalVars()   { return readsLocalVars; }
    public List<VariableNodeView>  getWritesLocalVars()  { return writesLocalVars; }
    public List<GraphNodeView>     getThrowsTypes()      { return throwsTypes; }
    public List<GraphNodeView>     getReferencesTypes()  { return referencesTypes; }
    public List<GraphNodeView>     getAnnotations()      { return annotations; }
    public List<LambdaNodeView>    getLambdas()          { return lambdas; }

    // ── Package-private mutators ──────────────────────────────────────────────
    void setDeclaredByClass(ClassNodeView v)    { this.declaredByClass = v; }
    void addCaller(GraphNodeView v)             { callers.add(v); }
    void addCallee(GraphNodeView v)             { callees.add(v); }
    void addInstantiates(ClassNodeView v)       { instantiates.add(v); }
    void addInstantiatesAnon(ClassNodeView v)   { instantiatesAnon.add(v); }
    void addReadsField(FieldNodeView v)         { readsFields.add(v); }
    void addWritesField(FieldNodeView v)        { writesFields.add(v); }
    void addReadsLocalVar(VariableNodeView v)   { readsLocalVars.add(v); }
    void addWritesLocalVar(VariableNodeView v)  { writesLocalVars.add(v); }
    void addThrowsType(GraphNodeView v)         { throwsTypes.add(v); }
    void addReferencesType(GraphNodeView v)     { referencesTypes.add(v); }
    void addAnnotation(GraphNodeView v)         { annotations.add(v); }
    void addLambda(LambdaNodeView v)            { lambdas.add(v); }
}
