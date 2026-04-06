package com.example.mrrag.view;

import com.example.mrrag.graph.GraphViewBuilder;
import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.*;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#METHOD} node.
 *
 * <p>Provides pre-populated lists for every edge type a method participates in,
 * so callers can traverse the full call-graph without touching raw edge maps:
 * <pre>{@code
 *   MethodNodeView m = builder.methodView("com.example.Foo#doWork(int)");
 *   m.getCallers();           // who calls this method
 *   m.getCallees();           // what this method calls
 *   m.getInstantiates();      // classes created with new inside this method
 *   m.getReadsFields();       // fields read by this method
 *   m.getWritesFields();      // fields written by this method
 *   m.getOverrides();         // super-method being overridden (or null)
 *   m.getOverriddenBy();      // child methods that override this one
 *   m.getTypeParameters();    // generic type params declared on this method
 *   m.getAnnotatedBy();       // annotations applied to this method (base)
 * }</pre>
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the method including its body.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}; it is populated from {@code ANNOTATED_WITH} outgoing
 * edges by {@link GraphViewBuilder}.
 *
 * <p><b>Edge line numbers</b> — {@link #addEdgeLine(String, int)} records the
 * source line at which each outgoing edge is used (call site, field read, etc.).
 * This data is consumed by {@link GraphNodeView#edgeLinesTo(String)} to produce
 * the {@code Lines:[...]} annotation in markdown output for external nodes.
 */
public class MethodNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /** The class that declares this method. Populated from the reverse DECLARES edge. */
    private ClassNodeView declaredByClass;

    // -------------------------------------------------------------------------
    // Generic type parameters
    // -------------------------------------------------------------------------

    /** Formal type parameters declared on this method, in declaration order. */
    private final List<TypeParamNodeView> typeParameters = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /** Callables that invoke this method (reverse INVOKES edges). */
    private final List<GraphNodeView> callers = new ArrayList<>();

    /** Methods, constructors, or lambdas invoked by this method (INVOKES outgoing). */
    private final List<GraphNodeView> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /** Classes instantiated via {@code new Foo(...)} inside this method body (INSTANTIATES). */
    private final List<ClassNodeView> instantiates = new ArrayList<>();

    /** Anonymous classes created inside this method body (INSTANTIATES_ANONYMOUS). */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();

    /** Method or constructor references used in this method (REFERENCES_METHOD). */
    private final List<GraphNodeView> referencedMethods = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /** Fields read by this method (READS_FIELD outgoing edges). */
    private final List<FieldNodeView> readsFields = new ArrayList<>();

    /** Fields written by this method (WRITES_FIELD outgoing edges). */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /** Local variables and parameters read by this method (READS_LOCAL_VAR). */
    private final List<VariableNodeView> readsLocalVars = new ArrayList<>();

    /** Local variables and parameters written by this method (WRITES_LOCAL_VAR). */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /** Exception types thrown inside this method body (THROWS outgoing edges). */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Type references
    // -------------------------------------------------------------------------

    /** Types referenced as values inside this method body (REFERENCES_TYPE). */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Inheritance
    // -------------------------------------------------------------------------

    /** The super-type method that this method overrides (OVERRIDES outgoing), or null. */
    private MethodNodeView overrides;

    /** Child methods in sub-classes that override this method (reverse OVERRIDES). */
    private final List<MethodNodeView> overriddenBy = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Lambdas
    // -------------------------------------------------------------------------

    /** Lambda expressions declared inside this method body (DECLARES → LAMBDA). */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Edge line numbers (used for Lines:[...] in external-node markdown output)
    // -------------------------------------------------------------------------

    /**
     * Maps target node id → list of source lines at which this method's outgoing
     * edges reference that target.  Populated by
     * {@link GraphViewBuilder} when wiring edges.
     * Excluded from {@code toMarkdown()} output (Map fields are skipped).
     */
    private final Map<String, List<Integer>> edgeLinesMap = new HashMap<>();

    public MethodNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ClassNodeView getDeclaredByClass()        { return declaredByClass; }
    public List<TypeParamNodeView> getTypeParameters() { return typeParameters; }
    public List<GraphNodeView> getCallers()           { return callers; }
    public List<GraphNodeView> getCallees()           { return callees; }
    public List<ClassNodeView> getInstantiates()      { return instantiates; }
    public List<ClassNodeView> getInstantiatesAnon()  { return instantiatesAnon; }
    public List<GraphNodeView> getReferencedMethods() { return referencedMethods; }
    public List<FieldNodeView> getReadsFields()       { return readsFields; }
    public List<FieldNodeView> getWritesFields()      { return writesFields; }
    public List<VariableNodeView> getReadsLocalVars()  { return readsLocalVars; }
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }
    public List<GraphNodeView> getThrowsTypes()       { return throwsTypes; }
    public List<GraphNodeView> getReferencesTypes()   { return referencesTypes; }
    public MethodNodeView getOverrides()              { return overrides; }
    public List<MethodNodeView> getOverriddenBy()     { return overriddenBy; }
    public List<LambdaNodeView> getLambdas()          { return lambdas; }

    // -------------------------------------------------------------------------
    // Edge line numbers
    // -------------------------------------------------------------------------

    /**
     * Records a source line at which this method references {@code targetId}.
     * Called by {@link GraphViewBuilder} when wiring
     * each outgoing edge so that external nodes display {@code Lines:[...]}.
     *
     * @param targetId raw node id of the referenced target
     * @param line     1-based source line number
     */
    public void addEdgeLine(String targetId, int line) {
        if (line > 0) {
            edgeLinesMap.computeIfAbsent(targetId, k -> new ArrayList<>()).add(line);
        }
    }

    @Override
    protected List<Integer> edgeLinesTo(String targetId) {
        return edgeLinesMap.getOrDefault(targetId, List.of());
    }

    @Override
    protected List<GraphNodeView> outgoingCallees() {
        return callees;
    }

    // -------------------------------------------------------------------------
    // Mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByClass(ClassNodeView v)     { this.declaredByClass = v; }
    public void addTypeParameter(TypeParamNodeView tp)  { typeParameters.add(tp); }
    public void addCaller(GraphNodeView v)              { callers.add(v); }
    public void addCallee(GraphNodeView v)              { callees.add(v); }
    public void addInstantiates(ClassNodeView v)        { instantiates.add(v); }
    public void addInstantiatesAnon(ClassNodeView v)    { instantiatesAnon.add(v); }
    public void addReferencedMethod(GraphNodeView v)    { referencedMethods.add(v); }
    public void addReadsField(FieldNodeView v)          { readsFields.add(v); }
    public void addWritesField(FieldNodeView v)         { writesFields.add(v); }
    public void addReadsLocalVar(VariableNodeView v)    { readsLocalVars.add(v); }
    public void addWritesLocalVar(VariableNodeView v)   { writesLocalVars.add(v); }
    public void addThrowsType(GraphNodeView v)          { throwsTypes.add(v); }
    public void addReferencesType(GraphNodeView v)      { referencesTypes.add(v); }
    public void setOverrides(MethodNodeView v)          { this.overrides = v; }
    public void addOverriddenBy(MethodNodeView v)       { overriddenBy.add(v); }
    public void addLambda(LambdaNodeView v)             { lambdas.add(v); }
}
