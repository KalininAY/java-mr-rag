package com.example.mrrag.view;

import com.example.mrrag.model.graph.NodeKind;
import com.example.mrrag.model.graph.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link NodeKind#INTERFACE} node.
 *
 * <p>Represents a Java {@code interface} declaration.
 * Differs from {@link ClassNodeView} in that interfaces:
 * <ul>
 *   <li>cannot be instantiated (no constructors, no {@code instantiatedBy});</li>
 *   <li>can only extend other interfaces ({@link #getExtendedInterfaces()});</li>
 *   <li>may declare {@code default} and {@code static} methods ({@link #getMethods()});</li>
 *   <li>may declare constants (fields with implicit {@code public static final}).</li>
 * </ul>
 *
 * <p>All list fields are pre-populated by
 * {@link com.example.mrrag.service.GraphViewBuilder} during its two-pass
 * edge-wiring step.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the interface declaration including its body.
 *
 * <p><b>Annotations</b> — use {@link #getAnnotatedBy()} inherited from
 * {@link GraphNodeView}.
 */
public class InterfaceNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Generic type parameters
    // -------------------------------------------------------------------------

    /**
     * Formal type parameters declared on this interface, in declaration order.
     * Example: {@code interface Foo<T extends Comparable<T>>}.
     * Populated from {@code HAS_TYPE_PARAM} outgoing edges.
     */
    private final List<TypeParamNodeView> typeParameters = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Inheritance
    // -------------------------------------------------------------------------

    /**
     * Interfaces directly extended by this interface
     * ({@code EXTENDS} outgoing edges, interface→interface).
     */
    private final List<InterfaceNodeView> extendedInterfaces = new ArrayList<>();

    /**
     * Known sub-interfaces that directly extend this interface
     * (reverse {@code EXTENDS} edges).
     */
    private final List<InterfaceNodeView> subInterfaces = new ArrayList<>();

    /**
     * Classes that directly implement this interface
     * (reverse {@code IMPLEMENTS} edges).
     */
    private final List<ClassNodeView> implementations = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Declared members
    // -------------------------------------------------------------------------

    /**
     * Methods declared by this interface ({@code DECLARES} outgoing edges to
     * {@code METHOD} nodes).
     * Includes abstract, default, and static interface methods.
     * Does <em>not</em> include inherited methods.
     */
    private final List<MethodNodeView> methods = new ArrayList<>();

    /**
     * Constants declared in this interface ({@code DECLARES} outgoing edges
     * to {@code FIELD} nodes).
     * In Java, all interface fields are implicitly {@code public static final}.
     */
    private final List<FieldNodeView> fields = new ArrayList<>();

    /**
     * Inner types (nested classes, nested interfaces) declared inside this
     * interface ({@code DECLARES} outgoing edges to {@code CLASS} or
     * {@code INTERFACE} nodes).
     */
    private final List<GraphNodeView> innerTypes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    /**
     * Nodes (methods, constructors, lambdas) that reference this interface
     * type in their signatures or bodies (reverse {@code REFERENCES_TYPE}
     * edges).
     */
    private final List<GraphNodeView> referencedBy = new ArrayList<>();

    /**
     * Nodes annotated with this interface when it is also an annotation type.
     * For regular interfaces this list will always be empty.
     */
    private final List<GraphNodeView> annotatedNodes = new ArrayList<>();

    public InterfaceNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns formal type parameters declared on this interface.
     * Empty for non-generic interfaces.
     *
     * @return list of type parameter views; never {@code null}
     */
    public List<TypeParamNodeView> getTypeParameters()         { return typeParameters; }

    /**
     * Returns the interfaces directly extended by this interface
     * (EXTENDS outgoing).
     *
     * @return list of super-interface views; never {@code null}
     */
    public List<InterfaceNodeView> getExtendedInterfaces()     { return extendedInterfaces; }

    /**
     * Returns the known sub-interfaces that directly extend this interface
     * (reverse EXTENDS edges).
     *
     * @return list of sub-interface views; never {@code null}
     */
    public List<InterfaceNodeView> getSubInterfaces()          { return subInterfaces; }

    /**
     * Returns the classes that directly implement this interface
     * (reverse IMPLEMENTS edges).
     *
     * @return list of implementing class views; never {@code null}
     */
    public List<ClassNodeView> getImplementations()            { return implementations; }

    /**
     * Returns the methods declared by this interface (DECLARES outgoing to
     * METHOD nodes), including abstract, default, and static methods.
     *
     * @return list of method views; never {@code null}
     */
    public List<MethodNodeView> getMethods()                   { return methods; }

    /**
     * Returns the constant fields declared in this interface
     * (DECLARES outgoing to FIELD nodes).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getFields()                     { return fields; }

    /**
     * Returns inner types (nested classes or interfaces) declared inside
     * this interface (DECLARES outgoing to CLASS/INTERFACE nodes).
     *
     * @return list of inner type views; never {@code null}
     */
    public List<GraphNodeView> getInnerTypes()                 { return innerTypes; }

    /**
     * Returns the nodes that reference this interface type
     * (reverse REFERENCES_TYPE edges).
     *
     * @return list of referencing node views; never {@code null}
     */
    public List<GraphNodeView> getReferencedBy()               { return referencedBy; }

    /**
     * Returns the nodes annotated with this type (non-empty only for
     * annotation-like interfaces; normally empty for regular interfaces).
     *
     * @return list of annotated node views; never {@code null}
     */
    public List<GraphNodeView> getAnnotatedNodes()             { return annotatedNodes; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void addTypeParameter(TypeParamNodeView tp)     { typeParameters.add(tp); }
    public void addExtendedInterface(InterfaceNodeView i)  { extendedInterfaces.add(i); }
    public void addSubInterface(InterfaceNodeView i)       { subInterfaces.add(i); }
    public void addImplementation(ClassNodeView cls)       { implementations.add(cls); }
    public void addMethod(MethodNodeView m)                { methods.add(m); }
    public void addField(FieldNodeView f)                  { fields.add(f); }
    public void addInnerType(GraphNodeView t)              { innerTypes.add(t); }
    public void addReferencedBy(GraphNodeView node)        { referencedBy.add(node); }
    public void addAnnotatedNode(GraphNodeView node)       { annotatedNodes.add(node); }
}
