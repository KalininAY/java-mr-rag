package com.example.mrrag.view;

import com.example.mrrag.model.graph.NodeKind;
import com.example.mrrag.model.graph.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link NodeKind#VARIABLE} node.
 *
 * <p>Represents a local variable declaration or a method / constructor
 * parameter.  Fields ({@code FIELD} nodes) are handled separately by
 * {@link FieldNodeView}.
 *
 * <p>{@link #getContent()} returns the Spoon pretty-printed variable
 * declaration statement, e.g. {@code "int x = 0;"} or the parameter
 * declaration, e.g. {@code "String name"}.
 */
public class VariableNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    /**
     * Callables (methods, constructors, lambdas) that read this variable
     * (reverse {@code READS_LOCAL_VAR} edges).
     *
     * <p>For a parameter this typically contains the single method that
     * declares it; for a local variable it contains all executables whose
     * body accesses the variable.
     */
    private final List<GraphNodeView> readBy = new ArrayList<>();

    /**
     * Callables (methods, constructors, lambdas) that write this variable
     * (reverse {@code WRITES_LOCAL_VAR} edges).
     */
    private final List<GraphNodeView> writtenBy = new ArrayList<>();

    public VariableNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the callables that read this variable
     * (reverse READS_LOCAL_VAR edges).
     *
     * @return list of reader views; never {@code null}
     */
    public List<GraphNodeView> getReadBy()    { return readBy; }

    /**
     * Returns the callables that write this variable
     * (reverse WRITES_LOCAL_VAR edges).
     *
     * @return list of writer views; never {@code null}
     */
    public List<GraphNodeView> getWrittenBy() { return writtenBy; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void addReadBy(GraphNodeView v)    { readBy.add(v); }
    public void addWrittenBy(GraphNodeView v) { writtenBy.add(v); }
}
