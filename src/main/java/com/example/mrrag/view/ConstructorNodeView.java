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
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the constructor including its body.
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
     */
    private final List<GraphNodeView> callers = new ArrayList<>();

    /**
     * Methods or constructors invoked inside this constructor body
     * ({@code INVOKES} outgoing edges).
     */
    private final List<GraphNodeView> callees = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this constructor
     * body ({@code INSTANTIATES} outgoing edges).
     */
    private final List<ClassNodeView> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * constructor body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read by this constructor ({@code READS_FIELD} outgoing edges).
     */
    private final List<FieldNodeView> readsFields = new ArrayList<>();

    /**
     * Fields written by this constructor ({@code WRITES_FIELD} outgoing edges).
     * Typically includes all fields assigned in the constructor body.
     */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read by this constructor
     * ({@code READS_LOCAL_VAR} outgoing edges).
     */
    private final List<VariableNodeView> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written by this constructor
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions, type references, annotations, lambdas
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this
     * constructor body ({@code THROWS} outgoing edges).
     */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    /**
     * Types referenced as values inside this constructor body
     * ({@code REFERENCES_TYPE} outgoing edges).
     *
     * <p>Covers {@code Foo.class} expressions, {@code instanceof Foo} checks,
     * and explicit cast expressions.
     */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

    /**
     * Annotation types applied to this constructor
     * ({@code ANNOTATED_WITH} outgoing edges).
     */
    private final List<GraphNodeView> annotations = new ArrayList<>();

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

    /**
     * Returns the class that declares this constructor.
     *
     * @return declaring class view; never {@code null} after wiring
     */
    public ClassNodeView getDeclaredByClass()       { return declaredByClass; }

    /**
     * Returns the callables that invoke this constructor
     * (reverse INVOKES / INSTANTIATES).
     *
     * @return list of caller views; never {@code null}
     */
    public List<GraphNodeView> getCallers()         { return callers; }

    /**
     * Returns the callables invoked inside this constructor body
     * (INVOKES outgoing).
     *
     * @return list of callee views; never {@code null}
     */
    public List<GraphNodeView> getCallees()         { return callees; }

    /**
     * Returns the classes instantiated via {@code new} inside this constructor
     * (INSTANTIATES outgoing).
     *
     * @return list of instantiated class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiates()    { return instantiates; }

    /**
     * Returns the anonymous classes created inside this constructor
     * (INSTANTIATES_ANONYMOUS outgoing).
     *
     * @return list of anonymous class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiatesAnon() { return instantiatesAnon; }

    /**
     * Returns the fields read by this constructor (READS_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getReadsFields()     { return readsFields; }

    /**
     * Returns the fields written by this constructor (WRITES_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getWritesFields()    { return writesFields; }

    /**
     * Returns the local variables and parameters read by this constructor
     * (READS_LOCAL_VAR outgoing).
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getReadsLocalVars()  { return readsLocalVars; }

    /**
     * Returns the local variables and parameters written by this constructor
     * (WRITES_LOCAL_VAR outgoing).
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }

    /**
     * Returns the exception types thrown inside this constructor body
     * (THROWS outgoing).
     *
     * @return list of exception type views; never {@code null}
     */
    public List<GraphNodeView> getThrowsTypes()     { return throwsTypes; }

    /**
     * Returns the types referenced as values inside this constructor body
     * (REFERENCES_TYPE outgoing): {@code Foo.class}, instanceof, casts.
     *
     * @return list of referenced type views; never {@code null}
     */
    public List<GraphNodeView> getReferencesTypes() { return referencesTypes; }

    /**
     * Returns the annotation types applied to this constructor
     * (ANNOTATED_WITH outgoing).
     *
     * @return list of annotation type views; never {@code null}
     */
    public List<GraphNodeView> getAnnotations()     { return annotations; }

    /**
     * Returns the lambda expressions declared inside this constructor body
     * (DECLARES outgoing to LAMBDA nodes).
     *
     * @return list of lambda views; never {@code null}
     */
    public List<LambdaNodeView> getLambdas()        { return lambdas; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

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
