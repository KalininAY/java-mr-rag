package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.*;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#CONSTRUCTOR} node.
 *
 * <p>Mirrors {@link MethodNodeView} in structure, but without
 * overrides / return-type concepts and without method-level type parameters
 * (Java constructors cannot carry their own type parameters independently).
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the constructor including its body.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}; it is populated from {@code ANNOTATED_WITH} outgoing
 * edges by {@link com.example.mrrag.service.GraphViewBuilder}.
 *
 * <p><b>Edge line numbers</b> — {@link #addEdgeLine(String, int)} records the
 * source line at which each outgoing edge is used (call site, field read, etc.).
 */
public class ConstructorNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /** The class that declares this constructor. Populated from the reverse DECLARES edge. */
    private ClassNodeView declaredByClass;

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /** Callables that invoke this constructor (reverse INVOKES / INSTANTIATES edges). */
    private final List<GraphNodeView> callers = new ArrayList<>();

    /** Methods or constructors invoked inside this constructor body (INVOKES outgoing). */
    private final List<GraphNodeView> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /** Classes instantiated via {@code new} inside this constructor body (INSTANTIATES). */
    private final List<ClassNodeView> instantiates = new ArrayList<>();

    /** Anonymous classes created inside this constructor body (INSTANTIATES_ANONYMOUS). */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /** Fields read by this constructor (READS_FIELD outgoing edges). */
    private final List<FieldNodeView> readsFields = new ArrayList<>();

    /** Fields written by this constructor (WRITES_FIELD outgoing edges). */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /** Local variables and parameters read by this constructor (READS_LOCAL_VAR). */
    private final List<VariableNodeView> readsLocalVars = new ArrayList<>();

    /** Local variables and parameters written by this constructor (WRITES_LOCAL_VAR). */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions, type references, lambdas
    // -------------------------------------------------------------------------

    /** Exception types thrown inside this constructor body (THROWS outgoing edges). */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    /** Types referenced as values inside this constructor body (REFERENCES_TYPE). */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

    /** Lambda expressions declared inside this constructor body (DECLARES → LAMBDA). */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Edge line numbers (used for Lines:[...] in external-node markdown output)
    // -------------------------------------------------------------------------

    /**
     * Maps target node id → list of source lines at which this constructor's
     * outgoing edges reference that target.  Populated by GraphViewBuilder.
     * Excluded from {@code toMarkdown()} output (Map fields are skipped).
     */
    private final Map<String, List<Integer>> edgeLinesMap = new HashMap<>();

    public ConstructorNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ClassNodeView getDeclaredByClass()       { return declaredByClass; }
    public List<GraphNodeView> getCallers()         { return callers; }
    public List<GraphNodeView> getCallees()         { return callees; }
    public List<ClassNodeView> getInstantiates()    { return instantiates; }
    public List<ClassNodeView> getInstantiatesAnon() { return instantiatesAnon; }
    public List<FieldNodeView> getReadsFields()     { return readsFields; }
    public List<FieldNodeView> getWritesFields()    { return writesFields; }
    public List<VariableNodeView> getReadsLocalVars()  { return readsLocalVars; }
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }
    public List<GraphNodeView> getThrowsTypes()     { return throwsTypes; }
    public List<GraphNodeView> getReferencesTypes() { return referencesTypes; }
    public List<LambdaNodeView> getLambdas()        { return lambdas; }

    // -------------------------------------------------------------------------
    // Edge line numbers
    // -------------------------------------------------------------------------

    /**
     * Records a source line at which this constructor references {@code targetId}.
     * Called by GraphViewBuilder when wiring each outgoing edge.
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

    public void setDeclaredByClass(ClassNodeView v)    { this.declaredByClass = v; }
    public void addCaller(GraphNodeView v)             { callers.add(v); }
    public void addCallee(GraphNodeView v)             { callees.add(v); }
    public void addInstantiates(ClassNodeView v)       { instantiates.add(v); }
    public void addInstantiatesAnon(ClassNodeView v)   { instantiatesAnon.add(v); }
    public void addReadsField(FieldNodeView v)         { readsFields.add(v); }
    public void addWritesField(FieldNodeView v)        { writesFields.add(v); }
    public void addReadsLocalVar(VariableNodeView v)   { readsLocalVars.add(v); }
    public void addWritesLocalVar(VariableNodeView v)  { writesLocalVars.add(v); }
    public void addThrowsType(GraphNodeView v)         { throwsTypes.add(v); }
    public void addReferencesType(GraphNodeView v)     { referencesTypes.add(v); }
    public void addLambda(LambdaNodeView v)            { lambdas.add(v); }
}
