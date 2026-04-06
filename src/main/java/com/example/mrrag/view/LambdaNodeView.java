package com.example.mrrag.view;

import com.example.mrrag.graph.GraphViewBuilder;
import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.*;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#LAMBDA} node.
 *
 * <p>A lambda is always declared inside a method or constructor body.  Its
 * unique id is {@code lambda@relPath:line}, e.g.
 * {@code lambda@src/main/java/com/example/Foo.java:42}.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed lambda expression
 * text, e.g. {@code "x -> x * 2"} or a full block body (used instead of the
 * enclosing statement's verbatim source lines for clearer markdown context).
 *
 * <p>All list fields are pre-populated by
 * {@link GraphViewBuilder}.
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

    // -------------------------------------------------------------------------
    // Edge line numbers (used for Lines:[...] in external-node markdown output)
    // -------------------------------------------------------------------------

    /**
     * Maps target node id → list of source lines at which this lambda's outgoing
     * edges reference that target.  Populated by
     * {@link GraphViewBuilder} when wiring edges.
     * Excluded from {@code toMarkdown()} output (Map fields are skipped).
     */
    private final Map<String, List<Integer>> edgeLinesMap = new HashMap<>();

    public LambdaNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public GraphNodeView getDeclaredByExecutable()    { return declaredByExecutable; }
    public List<GraphNodeView> getCallees()           { return callees; }
    public List<GraphNodeView> getCallers()           { return callers; }
    public List<ClassNodeView> getInstantiates()      { return instantiates; }
    public List<ClassNodeView> getInstantiatesAnon()  { return instantiatesAnon; }
    public List<FieldNodeView> getReadsFields()       { return readsFields; }
    public List<FieldNodeView> getWritesFields()      { return writesFields; }
    public List<VariableNodeView> getReadsLocalVars() { return readsLocalVars; }
    public List<VariableNodeView> getWritesLocalVars() { return writesLocalVars; }
    public List<GraphNodeView> getThrowsTypes()       { return throwsTypes; }
    public List<GraphNodeView> getReferencesTypes()   { return referencesTypes; }

    // -------------------------------------------------------------------------
    // Edge line numbers
    // -------------------------------------------------------------------------

    /**
     * Records a source line at which this lambda references {@code targetId}.
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
