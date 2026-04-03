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
 *   m.getCallers();           // who calls this method
 *   m.getCallees();           // what this method calls
 *   m.getInstantiates();      // classes created with new inside this method
 *   m.getReadsFields();       // fields read by this method
 *   m.getWritesFields();      // fields written by this method
 *   m.getOverrides();         // super-method being overridden (or null)
 *   m.getOverriddenBy();      // child methods that override this one
 *   m.getTypeParameters();    // generic type params declared on this method
 * }</pre>
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the method including its body.
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
     */
    private final List<GraphNodeView> callers = new ArrayList<>();

    /**
     * Methods, constructors, or lambdas invoked by this method
     * ({@code INVOKES} outgoing edges).
     */
    private final List<GraphNodeView> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this method body
     * ({@code INSTANTIATES} outgoing edges).
     */
    private final List<ClassNodeView> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * method body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();

    /**
     * Method or constructor references used in this method
     * ({@code REFERENCES_METHOD} outgoing edges).
     *
     * <p>Example: {@code list.forEach(Foo::bar)} produces one entry for
     * {@code Foo::bar}.
     */
    private final List<GraphNodeView> referencedMethods = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read by this method ({@code READS_FIELD} outgoing edges).
     */
    private final List<FieldNodeView> readsFields = new ArrayList<>();

    /**
     * Fields written by this method ({@code WRITES_FIELD} outgoing edges).
     */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read by this method
     * ({@code READS_LOCAL_VAR} outgoing edges).
     */
    private final List<VariableNodeView> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written by this method
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this method
     * body ({@code THROWS} outgoing edges).
     */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Type references
    // -------------------------------------------------------------------------

    /**
     * Types referenced as values inside this method body
     * ({@code REFERENCES_TYPE} outgoing edges).
     *
     * <p>Covers {@code Foo.class} expressions, {@code instanceof Foo} checks,
     * and explicit cast expressions.
     */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

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
    // Annotations
    // -------------------------------------------------------------------------

    /**
     * Annotation types applied to this method
     * ({@code ANNOTATED_WITH} outgoing edges).
     *
     * <p>Example: {@code @Override}, {@code @Transactional}.
     */
    private final List<GraphNodeView> annotations = new ArrayList<>();

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

    /**
     * Returns the class that declares this method.
     *
     * @return declaring class view; never {@code null} after wiring
     */
    public ClassNodeView getDeclaredByClass()        { return declaredByClass; }

    /**
     * Returns the formal type parameters declared on this method.
     * Empty for non-generic methods.
     *
     * @return list of type parameter views; never {@code null}
     */
    public List<TypeParamNodeView> getTypeParameters() { return typeParameters; }

    /**
     * Returns the callables that invoke this method (reverse INVOKES).
     *
     * @return list of caller views; never {@code null}
     */
    public List<GraphNodeView> getCallers()           { return callers; }

    /**
     * Returns the callables invoked by this method (INVOKES outgoing).
     *
     * @return list of callee views; never {@code null}
     */
    public List<GraphNodeView> getCallees()           { return callees; }

    /**
     * Returns the classes instantiated via {@code new} inside this method
     * (INSTANTIATES outgoing).
     *
     * @return list of instantiated class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiates()      { return instantiates; }

    /**
     * Returns the anonymous classes created inside this method
     * (INSTANTIATES_ANONYMOUS outgoing).
     *
     * @return list of anonymous class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiatesAnon()  { return instantiatesAnon; }

    /**
     * Returns the method/constructor references used in this method
     * (REFERENCES_METHOD outgoing), e.g. {@code Foo::bar}.
     *
     * @return list of referenced executable views; never {@code null}
     */
    public List<GraphNodeView> getReferencedMethods() { return referencedMethods; }

    /**
     * Returns the fields read by this method (READS_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getReadsFields()       { return readsFields; }

    /**
     * Returns the fields written by this method (WRITES_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getWritesFields()      { return writesFields; }

    /**
     * Returns the local variables and parameters read by this method
     * (READS_LOCAL_VAR outgoing).
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getReadsLocalVars()  { return readsLocalVars; }

    /**
     * Returns the local variables and parameters written by this method
     * (WRITES_LOCAL_VAR outgoing).
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }

    /**
     * Returns the exception types thrown inside this method body
     * (THROWS outgoing).
     *
     * @return list of exception type views; never {@code null}
     */
    public List<GraphNodeView> getThrowsTypes()       { return throwsTypes; }

    /**
     * Returns the types referenced as values inside this method body
     * (REFERENCES_TYPE outgoing): {@code Foo.class}, instanceof, casts.
     *
     * @return list of referenced type views; never {@code null}
     */
    public List<GraphNodeView> getReferencesTypes()   { return referencesTypes; }

    /**
     * Returns the super-type method overridden by this method
     * (OVERRIDES outgoing), or {@code null} if none.
     *
     * @return overridden method view, or {@code null}
     */
    public MethodNodeView getOverrides()              { return overrides; }

    /**
     * Returns the child methods that override this method
     * (reverse OVERRIDES edges).
     *
     * @return list of overriding method views; never {@code null}
     */
    public List<MethodNodeView> getOverriddenBy()     { return overriddenBy; }

    /**
     * Returns the annotation types applied to this method
     * (ANNOTATED_WITH outgoing).
     *
     * @return list of annotation type views; never {@code null}
     */
    public List<GraphNodeView> getAnnotations()       { return annotations; }

    /**
     * Returns the lambda expressions declared inside this method body
     * (DECLARES outgoing to LAMBDA nodes).
     *
     * @return list of lambda views; never {@code null}
     */
    public List<LambdaNodeView> getLambdas()          { return lambdas; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
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
    public void addAnnotation(GraphNodeView v)          { annotations.add(v); }
    public void addLambda(LambdaNodeView v)             { lambdas.add(v); }
}
