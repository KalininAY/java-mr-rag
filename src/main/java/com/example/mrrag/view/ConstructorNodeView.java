package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#CONSTRUCTOR} node.
 *
 * <p>Mirrors {@link MethodNodeView} in structure, but without
 * overrides / return-type concepts and without method-level type parameters
 * (Java constructors cannot carry their own type parameters independently).
 *
 * <p>All edge-based collections ({@code callers}, {@code callees},
 * {@code readsFields}, etc.) are {@code List<EdgeRef>}: each entry bundles
 * the neighbouring view with the 1-based source line of the edge so that
 * {@link GraphNodeView#toMarkdown()} can emit the exact call/access site.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the constructor including its body.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}; it is populated from {@code ANNOTATED_WITH} outgoing
 * edges by {@link com.example.mrrag.service.GraphViewBuilder}.
 */
public class ConstructorNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The class that declares this constructor.
     * Populated from the reverse of the {@code DECLARES} edge
     * ({@code CLASS → CONSTRUCTOR}).
     */
    private ClassNodeView declaredByClass;

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /**
     * Callables that invoke this constructor via {@code new Foo(...)},
     * {@code this(...)}, or {@code super(...)} (reverse {@code INVOKES} /
     * {@code INSTANTIATES} edges).
     * Each {@link EdgeRef} carries the line of the invocation.
     */
    private final List<EdgeRef> callers = new ArrayList<>();

    /**
     * Methods or constructors invoked inside this constructor body
     * ({@code INVOKES} outgoing edges).
     * Each {@link EdgeRef} carries the line in this constructor's body.
     */
    private final List<EdgeRef> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this constructor
     * body ({@code INSTANTIATES} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * constructor body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiatesAnon = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read by this constructor ({@code READS_FIELD} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsFields = new ArrayList<>();

    /**
     * Fields written by this constructor ({@code WRITES_FIELD} outgoing edges).
     * Typically includes all fields assigned in the constructor body.
     * Each {@link EdgeRef} carries the line of the assignment.
     */
    private final List<EdgeRef> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read by this constructor
     * ({@code READS_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written by this constructor
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the write expression.
     */
    private final List<EdgeRef> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions, type references, lambdas
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this
     * constructor body ({@code THROWS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code throw} statement.
     */
    private final List<EdgeRef> throwsTypes = new ArrayList<>();

    /**
     * Types referenced as values inside this constructor body
     * ({@code REFERENCES_TYPE} outgoing edges).
     * Each {@link EdgeRef} carries the line of the reference.
     */
    private final List<EdgeRef> referencesTypes = new ArrayList<>();

    /**
     * Lambda expressions declared inside this constructor body
     * ({@code DECLARES} outgoing edges to {@code LAMBDA} nodes).
     */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    public ConstructorNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ClassNodeView getDeclaredByClass()          { return declaredByClass; }
    public List<EdgeRef> getCallers()                  { return callers; }
    public List<EdgeRef> getCallees()                  { return callees; }
    public List<EdgeRef> getInstantiates()             { return instantiates; }
    public List<EdgeRef> getInstantiatesAnon()         { return instantiatesAnon; }
    public List<EdgeRef> getReadsFields()              { return readsFields; }
    public List<EdgeRef> getWritesFields()             { return writesFields; }
    public List<EdgeRef> getReadsLocalVars()           { return readsLocalVars; }
    public List<EdgeRef> getWritesLocalVars()          { return writesLocalVars; }
    public List<EdgeRef> getThrowsTypes()              { return throwsTypes; }
    public List<EdgeRef> getReferencesTypes()          { return referencesTypes; }
    public List<LambdaNodeView> getLambdas()           { return lambdas; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByClass(ClassNodeView v)             { this.declaredByClass = v; }
    public void addCaller(GraphNodeView v, int line)            { callers.add(EdgeRef.of(v, line)); }
    public void addCallee(GraphNodeView v, int line)            { callees.add(EdgeRef.of(v, line)); }
    public void addInstantiates(ClassNodeView v, int line)      { instantiates.add(EdgeRef.of(v, line)); }
    public void addInstantiatesAnon(ClassNodeView v, int line)  { instantiatesAnon.add(EdgeRef.of(v, line)); }
    public void addReadsField(FieldNodeView v, int line)        { readsFields.add(EdgeRef.of(v, line)); }
    public void addWritesField(FieldNodeView v, int line)       { writesFields.add(EdgeRef.of(v, line)); }
    public void addReadsLocalVar(VariableNodeView v, int line)  { readsLocalVars.add(EdgeRef.of(v, line)); }
    public void addWritesLocalVar(VariableNodeView v, int line) { writesLocalVars.add(EdgeRef.of(v, line)); }
    public void addThrowsType(GraphNodeView v, int line)        { throwsTypes.add(EdgeRef.of(v, line)); }
    public void addReferencesType(GraphNodeView v, int line)    { referencesTypes.add(EdgeRef.of(v, line)); }
    public void addLambda(LambdaNodeView v)                     { lambdas.add(v); }
}
