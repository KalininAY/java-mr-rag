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
 * <p>All edge-based collections ({@code callers}, {@code callees},
 * {@code readsFields}, etc.) are {@code List<EdgeRef>}: each entry bundles
 * the neighbouring view with the 1-based source line of the edge so that
 * {@link GraphNodeView#toMarkdown()} can emit the exact call/access site.
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
     * Each {@link EdgeRef} carries the line in this lambda's body.
     */
    private final List<EdgeRef> callees = new ArrayList<>();

    /**
     * Callables that reference this lambda directly.
     * In practice this list is rarely populated, since lambdas are usually
     * used inline rather than stored and re-invoked.
     * Each {@link EdgeRef} carries the line of the reference.
     */
    private final List<EdgeRef> callers = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Instantiation
    // -------------------------------------------------------------------------

    /**
     * Classes instantiated via {@code new Foo(...)} inside this lambda body
     * ({@code INSTANTIATES} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiates = new ArrayList<>();

    /**
     * Anonymous classes created via {@code new Foo() { ... }} inside this
     * lambda body ({@code INSTANTIATES_ANONYMOUS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code new} expression.
     */
    private final List<EdgeRef> instantiatesAnon = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Field access
    // -------------------------------------------------------------------------

    /**
     * Fields read inside this lambda body ({@code READS_FIELD} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsFields = new ArrayList<>();

    /**
     * Fields written inside this lambda body ({@code WRITES_FIELD} outgoing
     * edges).
     * Each {@link EdgeRef} carries the line of the write expression.
     */
    private final List<EdgeRef> writesFields = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Local variable access
    // -------------------------------------------------------------------------

    /**
     * Local variables and parameters read inside this lambda body
     * ({@code READS_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the read expression.
     */
    private final List<EdgeRef> readsLocalVars = new ArrayList<>();

    /**
     * Local variables and parameters written inside this lambda body
     * ({@code WRITES_LOCAL_VAR} outgoing edges).
     * Each {@link EdgeRef} carries the line of the write expression.
     */
    private final List<EdgeRef> writesLocalVars = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Exceptions and type references
    // -------------------------------------------------------------------------

    /**
     * Exception types thrown via {@code throw new Foo()} inside this lambda
     * body ({@code THROWS} outgoing edges).
     * Each {@link EdgeRef} carries the line of the {@code throw} statement.
     */
    private final List<EdgeRef> throwsTypes = new ArrayList<>();

    /**
     * Types referenced as values inside this lambda body
     * ({@code REFERENCES_TYPE} outgoing edges).
     * Each {@link EdgeRef} carries the line of the reference.
     */
    private final List<EdgeRef> referencesTypes = new ArrayList<>();

    public LambdaNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public GraphNodeView getDeclaredByExecutable()     { return declaredByExecutable; }
    public List<EdgeRef> getCallees()                  { return callees; }
    public List<EdgeRef> getCallers()                  { return callers; }
    public List<EdgeRef> getInstantiates()             { return instantiates; }
    public List<EdgeRef> getInstantiatesAnon()         { return instantiatesAnon; }
    public List<EdgeRef> getReadsFields()              { return readsFields; }
    public List<EdgeRef> getWritesFields()             { return writesFields; }
    public List<EdgeRef> getReadsLocalVars()           { return readsLocalVars; }
    public List<EdgeRef> getWritesLocalVars()          { return writesLocalVars; }
    public List<EdgeRef> getThrowsTypes()              { return throwsTypes; }
    public List<EdgeRef> getReferencesTypes()          { return referencesTypes; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void setDeclaredByExecutable(GraphNodeView v)        { this.declaredByExecutable = v; }
    public void addCallee(GraphNodeView v, int line)            { callees.add(EdgeRef.of(v, line)); }
    public void addCaller(GraphNodeView v, int line)            { callers.add(EdgeRef.of(v, line)); }
    public void addInstantiates(ClassNodeView v, int line)      { instantiates.add(EdgeRef.of(v, line)); }
    public void addInstantiatesAnon(ClassNodeView v, int line)  { instantiatesAnon.add(EdgeRef.of(v, line)); }
    public void addReadsField(FieldNodeView v, int line)        { readsFields.add(EdgeRef.of(v, line)); }
    public void addWritesField(FieldNodeView v, int line)       { writesFields.add(EdgeRef.of(v, line)); }
    public void addReadsLocalVar(VariableNodeView v, int line)  { readsLocalVars.add(EdgeRef.of(v, line)); }
    public void addWritesLocalVar(VariableNodeView v, int line) { writesLocalVars.add(EdgeRef.of(v, line)); }
    public void addThrowsType(GraphNodeView v, int line)        { throwsTypes.add(EdgeRef.of(v, line)); }
    public void addReferencesType(GraphNodeView v, int line)    { referencesTypes.add(EdgeRef.of(v, line)); }
}
