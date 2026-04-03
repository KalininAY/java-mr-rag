package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#LAMBDA} node.
 *
 * <p>A lambda is always declared inside a method or constructor body.  Its
 * unique id is {@code lambda@relPath:line}, e.g.
 * {@code lambda@src/main/java/com/example/Foo.java:42}.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed lambda expression
 * text, e.g. {@code "x -> x * 2"} or a full block body.
 *
 * <p>All list fields are pre-populated by
 * {@link com.example.mrrag.service.GraphViewBuilder}.
 */
public class LambdaNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Ownership
    // -------------------------------------------------------------------------

    /**
     * The method or constructor that contains this lambda expression
     * (reverse {@code DECLARES} edge: executable → lambda).
     *
     * <p>Never {@code null} after the graph is wired.
     */
    private GraphNodeView declaredByExecutable;

    // -------------------------------------------------------------------------
    // Call graph
    // -------------------------------------------------------------------------

    /**
     * Methods or constructors invoked inside this lambda body
     * ({@code INVOKES} outgoing edges).
     */
    private final List<GraphNodeView> callees = new ArrayList<>();

    /**
     * Callables that reference this lambda directly.
     * In practice this list is rarely populated, since lambdas are usually
     * used inline rather than stored and re-invoked.
     */
    private final List<GraphNodeView> callers = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this lambda body
     * ({@code INSTANTIATES} outgoing edges).
     */
    private final List<ClassNodeView> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * lambda body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     */
    private final List<ClassNodeView> instantiatesAnon = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read inside this lambda body ({@code READS_FIELD} outgoing edges).
     *
     * <p>Commonly includes captured enclosing-instance fields.
     */
    private final List<FieldNodeView> readsFields = new ArrayList<>();

    /**
     * Fields written inside this lambda body ({@code WRITES_FIELD} outgoing
     * edges).
     */
    private final List<FieldNodeView> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read inside this lambda body
     * ({@code READS_LOCAL_VAR} outgoing edges).
     *
     * <p>Includes effectively-final variables captured from the enclosing scope.
     */
    private final List<VariableNodeView> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written inside this lambda body
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     */
    private final List<VariableNodeView> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions and type references
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this lambda
     * body ({@code THROWS} outgoing edges).
     */
    private final List<GraphNodeView> throwsTypes = new ArrayList<>();

    /**
     * Types referenced as values inside this lambda body
     * ({@code REFERENCES_TYPE} outgoing edges).
     *
     * <p>Covers {@code Foo.class} expressions, {@code instanceof Foo} checks,
     * and explicit cast expressions.
     */
    private final List<GraphNodeView> referencesTypes = new ArrayList<>();

    public LambdaNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the method or constructor that contains this lambda
     * (reverse DECLARES edge).
     *
     * @return enclosing executable view; never {@code null} after wiring
     */
    public GraphNodeView getDeclaredByExecutable()    { return declaredByExecutable; }

    /**
     * Returns the callables invoked inside this lambda body
     * (INVOKES outgoing).
     *
     * @return list of callee views; never {@code null}
     */
    public List<GraphNodeView> getCallees()           { return callees; }

    /**
     * Returns the callables that directly reference this lambda.
     * Usually empty for inline lambdas.
     *
     * @return list of caller views; never {@code null}
     */
    public List<GraphNodeView> getCallers()           { return callers; }

    /**
     * Returns the classes instantiated via {@code new} inside this lambda
     * (INSTANTIATES outgoing).
     *
     * @return list of instantiated class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiates()      { return instantiates; }

    /**
     * Returns the anonymous classes created inside this lambda
     * (INSTANTIATES_ANONYMOUS outgoing).
     *
     * @return list of anonymous class views; never {@code null}
     */
    public List<ClassNodeView> getInstantiatesAnon()  { return instantiatesAnon; }

    /**
     * Returns the fields read inside this lambda body (READS_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getReadsFields()       { return readsFields; }

    /**
     * Returns the fields written inside this lambda body (WRITES_FIELD outgoing).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getWritesFields()      { return writesFields; }

    /**
     * Returns the local variables and parameters read inside this lambda
     * (READS_LOCAL_VAR outgoing), including captured variables.
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getReadsLocalVars() { return readsLocalVars; }

    /**
     * Returns the local variables and parameters written inside this lambda
     * (WRITES_LOCAL_VAR outgoing).
     *
     * @return list of variable views; never {@code null}
     */
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }

    /**
     * Returns the exception types thrown inside this lambda body
     * (THROWS outgoing).
     *
     * @return list of exception type views; never {@code null}
     */
    public List<GraphNodeView> getThrowsTypes()       { return throwsTypes; }

    /**
     * Returns the types referenced as values inside this lambda body
     * (REFERENCES_TYPE outgoing): {@code Foo.class}, instanceof, casts.
     *
     * @return list of referenced type views; never {@code null}
     */
    public List<GraphNodeView> getReferencesTypes()   { return referencesTypes; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByExecutable(GraphNodeView v) { this.declaredByExecutable = v; }
    public void addCallee(GraphNodeView v)               { callees.add(v); }
    public void addCaller(GraphNodeView v)               { callers.add(v); }
    public void addInstantiates(ClassNodeView v)         { instantiates.add(v); }
    public void addInstantiatesAnon(ClassNodeView v)     { instantiatesAnon.add(v); }
    public void addReadsField(FieldNodeView v)           { readsFields.add(v); }
    public void addWritesField(FieldNodeView v)          { writesFields.add(v); }
    public void addReadsLocalVar(VariableNodeView v)     { readsLocalVars.add(v); }
    public void addWritesLocalVar(VariableNodeView v)    { writesLocalVars.add(v); }
    public void addThrowsType(GraphNodeView v)           { throwsTypes.add(v); }
    public void addReferencesType(GraphNodeView v)       { referencesTypes.add(v); }
}
