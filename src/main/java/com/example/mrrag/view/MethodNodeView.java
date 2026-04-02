package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code METHOD} node.
 *
 * <p>Provides pre-populated lists for every edge type a method participates in,
 * so callers can traverse the full call-graph without touching raw edge maps:
 * <pre>{@code
 *   MethodNodeView m = builder.methodById("com.example.Foo#doWork()");
 *   m.getCallers();          // who calls this method
 *   m.getCallees();          // what this method calls
 *   m.getInstantiates();     // classes created with new inside this method
 *   m.getReadsFields();      // fields read
 *   m.getWritesFields();     // fields written
 *   m.getOverrides();        // super-method being overridden (or null)
 *   m.getOverriddenBy();     // child methods that override this one
 * }</pre>
 */
public class MethodNodeView extends GraphNodeView {

    // ── Ownership ─────────────────────────────────────────────────────────────
    /** Class that declares this method. */
    private ClassNodeView declaredByClass;

    // ── Call graph ────────────────────────────────────────────────────────────
    /** Callables (methods / constructors / lambdas) that invoke this method. */
    private final List<GraphNodeView> callers = new ArrayList<>();
    /** Methods, constructors, or lambdas invoked by this method. */
    private final List<GraphNodeView> callees = new ArrayList<>();

    // ── Instantiation ─────────────────────────────────────────────────────────
    /** Classes instantiated via {@code new Foo()} inside this method body. */
    private final List<ClassNodeView> instantiates     = new ArrayList<>();
    /** Anonymous classes created inside this method body. */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();
    /** Method / constructor references ({@code Foo::bar}) used in this method. */
    private final List<GraphNodeView> referencedMethods = new ArrayList<>();

    // ── Field access ──────────────────────────────────────────────────────────
    /** Fields read by this method. */
    private final List<FieldNodeView> readsFields  = new ArrayList<>();
    /** Fields written by this method. */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // ── Local variable access ─────────────────────────────────────────────────
    /** Local variables / parameters read by this method. */
    private final List<VariableNodeView> readsLocalVars  = new ArrayList<>();
    /** Local variables / parameters written by this method. */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // ── Exceptions ────────────────────────────────────────────────────────────
    /** Exception types thrown via {@code throw new ...} in this method body. */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    // ── Type references ───────────────────────────────────────────────────────
    /** Types referenced as values ({@code Foo.class}, instanceof, cast). */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

    // ── Inheritance ───────────────────────────────────────────────────────────
    /** The super-type method this method overrides, or {@code null}. */
    private MethodNodeView overrides;
    /** Child methods in sub-classes that override this method. */
    private final List<MethodNodeView> overriddenBy = new ArrayList<>();

    // ── Annotations ───────────────────────────────────────────────────────────
    /** Annotation types applied to this method. */
    private final List<GraphNodeView> annotations = new ArrayList<>();

    // ── Lambdas ───────────────────────────────────────────────────────────────
    /** Lambda expressions declared in this method body. */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    public MethodNodeView(GraphNode node) {
        super(node);
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public ClassNodeView           getDeclaredByClass()   { return declaredByClass; }
    public List<GraphNodeView>     getCallers()           { return callers; }
    public List<GraphNodeView>     getCallees()           { return callees; }
    public List<ClassNodeView>     getInstantiates()      { return instantiates; }
    public List<ClassNodeView>     getInstantiatesAnon()  { return instantiatesAnon; }
    public List<GraphNodeView>     getReferencedMethods() { return referencedMethods; }
    public List<FieldNodeView>     getReadsFields()       { return readsFields; }
    public List<FieldNodeView>     getWritesFields()      { return writesFields; }
    public List<VariableNodeView>  getReadsLocalVars()    { return readsLocalVars; }
    public List<VariableNodeView>  getWritesLocalVars()   { return writesLocalVars; }
    public List<GraphNodeView>     getThrowsTypes()       { return throwsTypes; }
    public List<GraphNodeView>     getReferencesTypes()   { return referencesTypes; }
    public MethodNodeView          getOverrides()         { return overrides; }
    public List<MethodNodeView>    getOverriddenBy()      { return overriddenBy; }
    public List<GraphNodeView>     getAnnotations()       { return annotations; }
    public List<LambdaNodeView>    getLambdas()           { return lambdas; }

    // ── Package-private mutators ──────────────────────────────────────────────
    void setDeclaredByClass(ClassNodeView v)     { this.declaredByClass = v; }
    void addCaller(GraphNodeView v)              { callers.add(v); }
    void addCallee(GraphNodeView v)              { callees.add(v); }
    void addInstantiates(ClassNodeView v)        { instantiates.add(v); }
    void addInstantiatesAnon(ClassNodeView v)    { instantiatesAnon.add(v); }
    void addReferencedMethod(GraphNodeView v)    { referencedMethods.add(v); }
    void addReadsField(FieldNodeView v)          { readsFields.add(v); }
    void addWritesField(FieldNodeView v)         { writesFields.add(v); }
    void addReadsLocalVar(VariableNodeView v)    { readsLocalVars.add(v); }
    void addWritesLocalVar(VariableNodeView v)   { writesLocalVars.add(v); }
    void addThrowsType(GraphNodeView v)          { throwsTypes.add(v); }
    void addReferencesType(GraphNodeView v)      { referencesTypes.add(v); }
    void setOverrides(MethodNodeView v)          { this.overrides = v; }
    void addOverriddenBy(MethodNodeView v)       { overriddenBy.add(v); }
    void addAnnotation(GraphNodeView v)          { annotations.add(v); }
    void addLambda(LambdaNodeView v)             { lambdas.add(v); }
}
