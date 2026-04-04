package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#METHOD} node.
 *
 * <p>Provides pre-populated lists for every edge type a method participates in,
 * so callers can traverse the full call-graph without touching raw edge maps:
 * <pre>{@code
 *   MethodNodeView m = builder.methodView("com.example.Foo#doWork(int)");
 *   m.getCallers();           // who calls this method (with call-site line)
 *   m.getCallees();           // what this method calls (with call-site line)
 *   m.getInstantiates();      // classes created with new inside this method
 *   m.getReadsFields();       // fields read by this method
 *   m.getWritesFields();      // fields written by this method
 *   m.getOverrides();         // super-method being overridden (or null)
 *   m.getOverriddenBy();      // child methods that override this one
 *   m.getTypeParameters();    // generic type params declared on this method
 *   m.getAnnotatedBy();       // annotations applied to this method (base)
 * }</pre>
 *
 * <p>All edge-based collections ({@code callers}, {@code callees},
 * {@code readsFields}, etc.) are {@code List<EdgeRef>}: each entry bundles
 * the neighbouring view with the 1-based source line of the edge so that
 * {@link GraphNodeView#toMarkdown()} can emit the exact call/access site.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the method including its body.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}; it is populated from {@code ANNOTATED_WITH} outgoing
 * edges by {@link com.example.mrrag.service.GraphViewBuilder}.
 */
public class MethodNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The class that declares this method.
     * Populated from the reverse of the {@code DECLARES} edge
     * ({@code CLASS → METHOD}).
     */
    private ClassNodeView declaredByClass;

    // -------------------------------------------------------------------------
    // Generic type parameters
    // -------------------------------------------------------------------------

    /**
     * Formal type parameters declared on this method, in declaration order.
     *
     * <p>Example: {@code <R> R map(Function<T, R> f)} produces one
     * {@link TypeParamNodeView} entry for {@code R}.
     * Empty for non-generic methods.
     *
     * <p>Populated from {@code HAS_TYPE_PARAM} outgoing edges.
     */
    private final List<TypeParamNodeView> typeParameters = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /**
     * Callables (methods, constructors, lambdas) that invoke this method
     * (reverse {@code INVOKES} edges).
     * Each {@link EdgeRef} carries the line in the <em>caller's</em> body
     * where the invocation occurs.
     */
    private final List<EdgeRef> callers = new ArrayList<>();

    /**
     * Methods, constructors, or lambdas invoked by this method
     * ({@code INVOKES} outgoing edges).
     * Each {@link EdgeRef} carries the line in <em>this</em> method's body
     * where the invocation occurs.
     */
    private final List<EdgeRef> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this method body
     * ({@code INSTANTIATES} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * method body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiatesAnon = new ArrayList<>();

    /**
     * Method or constructor references used in this method
     * ({@code REFERENCES_METHOD} outgoing edges).
     *
     * <p>Example: {@code list.forEach(Foo::bar)} produces one entry for
     * {@code Foo::bar}.
     */
    private final List<EdgeRef> referencedMethods = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read by this method ({@code READS_FIELD} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsFields = new ArrayList<>();

    /**
     * Fields written by this method ({@code WRITES_FIELD} outgoing edges).
     * Each {@link EdgeRef} carries the line of the write expression.
     */
    private final List<EdgeRef> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read by this method
     * ({@code READS_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written by this method
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the write expression.
     */
    private final List<EdgeRef> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this method
     * body ({@code THROWS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code throw} statement.
     */
    private final List<EdgeRef> throwsTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Type references
    // -------------------------------------------------------------------------

    /**
     * Types referenced as values inside this method body
     * ({@code REFERENCES_TYPE} outgoing edges).
     *
     * <p>Covers {@code Foo.class} expressions, {@code instanceof Foo} checks,
     * and explicit cast expressions.
     * Each {@link EdgeRef} carries the line of the reference.
     */
    private final List<EdgeRef> referencesTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Inheritance
    // -------------------------------------------------------------------------

    /**
     * The super-type method that this method overrides
     * ({@code OVERRIDES} outgoing edge), or {@code null} if this method does
     * not override anything tracked in the graph.
     */
    private MethodNodeView overrides;

    /**
     * Child methods in sub-classes that override this method
     * (reverse {@code OVERRIDES} edges).
     */
    private final List<MethodNodeView> overriddenBy = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Lambdas
    // -------------------------------------------------------------------------

    /**
     * Lambda expressions declared inside this method body
     * ({@code DECLARES} outgoing edges to {@code LAMBDA} nodes).
     */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    public MethodNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ClassNodeView getDeclaredByClass()           { return declaredByClass; }
    public List<TypeParamNodeView> getTypeParameters()  { return typeParameters; }
    public List<EdgeRef> getCallers()                   { return callers; }
    public List<EdgeRef> getCallees()                   { return callees; }
    public List<EdgeRef> getInstantiates()              { return instantiates; }
    public List<EdgeRef> getInstantiatesAnon()          { return instantiatesAnon; }
    public List<EdgeRef> getReferencedMethods()         { return referencedMethods; }
    public List<EdgeRef> getReadsFields()               { return readsFields; }
    public List<EdgeRef> getWritesFields()              { return writesFields; }
    public List<EdgeRef> getReadsLocalVars()            { return readsLocalVars; }
    public List<EdgeRef> getWritesLocalVars()           { return writesLocalVars; }
    public List<EdgeRef> getThrowsTypes()               { return throwsTypes; }
    public List<EdgeRef> getReferencesTypes()           { return referencesTypes; }
    public MethodNodeView getOverrides()                { return overrides; }
    public List<MethodNodeView> getOverriddenBy()       { return overriddenBy; }
    public List<LambdaNodeView> getLambdas()            { return lambdas; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByClass(ClassNodeView v)             { this.declaredByClass = v; }
    public void addTypeParameter(TypeParamNodeView tp)          { typeParameters.add(tp); }
    public void addCaller(GraphNodeView v, int line)            { callers.add(EdgeRef.of(v, line)); }
    public void addCallee(GraphNodeView v, int line)            { callees.add(EdgeRef.of(v, line)); }
    public void addInstantiates(ClassNodeView v, int line)      { instantiates.add(EdgeRef.of(v, line)); }
    public void addInstantiatesAnon(ClassNodeView v, int line)  { instantiatesAnon.add(EdgeRef.of(v, line)); }
    public void addReferencedMethod(GraphNodeView v, int line)  { referencedMethods.add(EdgeRef.of(v, line)); }
    public void addReadsField(FieldNodeView v, int line)        { readsFields.add(EdgeRef.of(v, line)); }
    public void addWritesField(FieldNodeView v, int line)       { writesFields.add(EdgeRef.of(v, line)); }
    public void addReadsLocalVar(VariableNodeView v, int line)  { readsLocalVars.add(EdgeRef.of(v, line)); }
    public void addWritesLocalVar(VariableNodeView v, int line) { writesLocalVars.add(EdgeRef.of(v, line)); }
    public void addThrowsType(GraphNodeView v, int line)        { throwsTypes.add(EdgeRef.of(v, line)); }
    public void addReferencesType(GraphNodeView v, int line)    { referencesTypes.add(EdgeRef.of(v, line)); }
    public void setOverrides(MethodNodeView v)                  { this.overrides = v; }
    public void addOverriddenBy(MethodNodeView v)               { overriddenBy.add(v); }
    public void addLambda(LambdaNodeView v)                     { lambdas.add(v); }
}
