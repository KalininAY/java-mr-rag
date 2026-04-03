package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#CLASS} node.
 *
 * <p>Covers Java classes, interfaces, enums, record types, and annotation types
 * ({@code @interface}).  All list fields are pre-populated by
 * {@link com.example.mrrag.service.GraphViewBuilder} during its two-pass
 * edge-wiring step, so callers receive a fully connected view object with
 * no further look-ups required.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source of
 * the type declaration including its body.
 *
 * <h2>Generics</h2>
 * Formal type parameters declared on this class are available via
 * {@link #getTypeParameters()}.  Each {@link TypeParamNodeView} entry carries
 * its own bound list and a back-reference to its owner.
 *
 * <h2>Annotation types</h2>
 * When this node represents an {@code @interface}, the attribute methods
 * (e.g. {@code String value() default ""}) are exposed via
 * {@link #getAnnotationAttributes()} instead of {@link #getMethods()}.
 */
public class ClassNodeView extends GraphNodeView {

    // -------------------------------------------------------------------------
    // Generic type parameters
    // -------------------------------------------------------------------------

    /**
     * Formal type parameters declared on this class, in declaration order.
     *
     * <p>Example: {@code class Foo<T, K extends Comparable<K>>}
     * produces a list of two {@link TypeParamNodeView} entries: {@code T} and
     * {@code K}. Empty for non-generic classes.
     *
     * <p>Populated from {@code HAS_TYPE_PARAM} outgoing edges.
     */
    private final List<TypeParamNodeView> typeParameters = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Inheritance
    // -------------------------------------------------------------------------

    /**
     * The direct superclass of this type, i.e. the target of the
     * {@code EXTENDS} edge, or {@code null} if none is recorded in the graph.
     *
     * <p>For an external superclass (e.g. {@code Object}) this will be a
     * lightweight stub {@link ClassNodeView} with an empty content string.
     */
    private ClassNodeView superClass;

    /**
     * Interfaces directly implemented or extended by this type.
     *
     * <p>Populated from {@code IMPLEMENTS} outgoing edges.
     * Each entry may be a stub for external interface types.
     */
    private final List<ClassNodeView> interfaces = new ArrayList<>();

    /**
     * Direct known subclasses of this class (reverse {@code EXTENDS} edges).
     *
     * <p>Only subclasses present in the analysed project are listed;
     * external subclasses are not tracked.
     */
    private final List<ClassNodeView> subClasses = new ArrayList<>();

    /**
     * Types that directly implement this interface (reverse {@code IMPLEMENTS}
     * edges).
     *
     * <p>Only implementations present in the analysed project are listed.
     */
    private final List<ClassNodeView> implementations = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Declared members
    // -------------------------------------------------------------------------

    /**
     * Methods declared by this type ({@code DECLARES} outgoing edges to
     * {@code METHOD} nodes).
     *
     * <p>Does <em>not</em> include inherited methods.
     */
    private final List<MethodNodeView> methods = new ArrayList<>();

    /**
     * Constructors declared by this type ({@code DECLARES} outgoing edges to
     * {@code CONSTRUCTOR} nodes).
     */
    private final List<ConstructorNodeView> constructors = new ArrayList<>();

    /**
     * Fields declared by this type ({@code DECLARES} outgoing edges to
     * {@code FIELD} nodes).
     *
     * <p>Does <em>not</em> include inherited fields.
     */
    private final List<FieldNodeView> fields = new ArrayList<>();

    /**
     * Inner / nested / anonymous classes declared inside this type
     * (reverse {@code DECLARES} edges from this type to other {@code CLASS}
     * nodes).
     */
    private final List<ClassNodeView> innerClasses = new ArrayList<>();

    /**
     * Lambda expressions directly declared inside this type's static
     * initialiser or field initialisers ({@code DECLARES} → {@code LAMBDA}).
     *
     * <p>Lambdas inside method bodies are linked to their enclosing method
     * via {@link MethodNodeView#getLambdas()} instead.
     */
    private final List<LambdaNodeView> lambdas = new ArrayList<>();

    /**
     * Attribute method elements declared inside this {@code @interface}.
     *
     * <p>Populated from {@code ANNOTATION_ATTR} outgoing edges.
     * Empty for regular (non-annotation) types.
     *
     * <p>Example: for {@code @interface Retry { int times() default 3; }}
     * this list contains one {@link AnnotationAttributeView} for {@code times}.
     */
    private final List<AnnotationAttributeView> annotationAttributes = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    /**
     * Executables (methods, constructors, lambdas) that instantiate this
     * class via {@code new Foo(...)} (reverse {@code INSTANTIATES} edges).
     */
    private final List<GraphNodeView> instantiatedBy = new ArrayList<>();

    /**
     * Executables (methods, constructors, lambdas) that instantiate this
     * class anonymously via {@code new Foo() { ... }}
     * (reverse {@code INSTANTIATES_ANONYMOUS} edges).
     */
    private final List<GraphNodeView> anonymouslyInstantiatedBy = new ArrayList<>();

    /**
     * Nodes (classes, methods, fields) that are annotated with this type
     * (reverse {@code ANNOTATED_WITH} edges).
     *
     * <p>Non-empty only when this class represents an annotation type.
     */
    private final List<GraphNodeView> annotatedNodes = new ArrayList<>();

    public ClassNodeView(GraphNode node) {
        super(node);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the formal type parameters declared on this class, in order.
     * Empty list for non-generic types.
     *
     * @return list of type parameter views; never {@code null}
     */
    public List<TypeParamNodeView> getTypeParameters()       { return typeParameters; }

    /**
     * Returns the direct superclass view, or {@code null} if none is tracked.
     *
     * @return superclass view, possibly a stub; may be {@code null}
     */
    public ClassNodeView getSuperClass()                     { return superClass; }

    /**
     * Returns the interfaces directly implemented or extended by this type.
     *
     * @return list of interface views; never {@code null}
     */
    public List<ClassNodeView> getInterfaces()               { return interfaces; }

    /**
     * Returns the direct known subclasses of this class.
     *
     * @return list of subclass views; never {@code null}
     */
    public List<ClassNodeView> getSubClasses()               { return subClasses; }

    /**
     * Returns the types that directly implement this interface.
     *
     * @return list of implementation views; never {@code null}
     */
    public List<ClassNodeView> getImplementations()          { return implementations; }

    /**
     * Returns the methods declared by this type (excluding inherited ones).
     *
     * @return list of method views; never {@code null}
     */
    public List<MethodNodeView> getMethods()                 { return methods; }

    /**
     * Returns the constructors declared by this type.
     *
     * @return list of constructor views; never {@code null}
     */
    public List<ConstructorNodeView> getConstructors()       { return constructors; }

    /**
     * Returns the fields declared by this type (excluding inherited ones).
     *
     * @return list of field views; never {@code null}
     */
    public List<FieldNodeView> getFields()                   { return fields; }

    /**
     * Returns inner, nested, or anonymous classes declared inside this type.
     *
     * @return list of inner class views; never {@code null}
     */
    public List<ClassNodeView> getInnerClasses()             { return innerClasses; }

    /**
     * Returns lambda expressions declared in static/field initialisers of
     * this type.  Lambdas inside method bodies are on
     * {@link MethodNodeView#getLambdas()} instead.
     *
     * @return list of lambda views; never {@code null}
     */
    public List<LambdaNodeView> getLambdas()                 { return lambdas; }

    /**
     * Returns the attribute elements declared inside this {@code @interface}.
     * Empty list for regular (non-annotation) types.
     *
     * @return list of annotation attribute views; never {@code null}
     */
    public List<AnnotationAttributeView> getAnnotationAttributes() { return annotationAttributes; }

    /**
     * Returns the executables that instantiate this class via {@code new Foo(...)}.
     *
     * @return list of caller views; never {@code null}
     */
    public List<GraphNodeView> getInstantiatedBy()           { return instantiatedBy; }

    /**
     * Returns the executables that instantiate this class anonymously via
     * {@code new Foo() { ... }} (reverse {@code INSTANTIATES_ANONYMOUS} edges).
     *
     * @return list of caller views; never {@code null}
     */
    public List<GraphNodeView> getAnonymouslyInstantiatedBy() { return anonymouslyInstantiatedBy; }

    /**
     * Returns the nodes annotated with this annotation type.
     * Non-empty only when this class is an annotation type.
     *
     * @return list of annotated node views; never {@code null}
     */
    public List<GraphNodeView> getAnnotatedNodes()           { return annotatedNodes; }

    // -------------------------------------------------------------------------
    // Package-private mutators used by GraphViewBuilder
    // -------------------------------------------------------------------------

    public void addTypeParameter(TypeParamNodeView tp)            { typeParameters.add(tp); }
    public void setSuperClass(ClassNodeView superClass)           { this.superClass = superClass; }
    public void addInterface(ClassNodeView iface)                 { interfaces.add(iface); }
    public void addSubClass(ClassNodeView sub)                    { subClasses.add(sub); }
    public void addImplementation(ClassNodeView impl)             { implementations.add(impl); }
    public void addMethod(MethodNodeView m)                       { methods.add(m); }
    public void addConstructor(ConstructorNodeView c)             { constructors.add(c); }
    public void addField(FieldNodeView f)                         { fields.add(f); }
    public void addInnerClass(ClassNodeView inner)                { innerClasses.add(inner); }
    public void addLambda(LambdaNodeView l)                       { lambdas.add(l); }
    public void addAnnotationAttribute(AnnotationAttributeView a) { annotationAttributes.add(a); }
    public void addInstantiatedBy(GraphNodeView caller)           { instantiatedBy.add(caller); }
    public void addAnonymouslyInstantiatedBy(GraphNodeView caller){ anonymouslyInstantiatedBy.add(caller); }
    public void addAnnotatedNode(GraphNodeView node)              { annotatedNodes.add(node); }
}
