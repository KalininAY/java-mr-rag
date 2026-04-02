package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@link com.example.mrrag.service.AstGraphService.NodeKind#CLASS} node.
 *
 * <p>Covers classes, interfaces, enums, record types, and annotation types
 * ({@code @interface}).  All list fields are pre-populated by
 * {@link com.example.mrrag.service.GraphViewBuilder} from the two-pass
 * edge-wiring step.
 *
 * <p>{@link #getContent()} returns the full Spoon pretty-printed source
 * of the type declaration, including body.
 *
 * <h2>Generics</h2>
 * The type parameters declared on this class are exposed via
 * {@link #getTypeParameters()}.  Each entry is a {@link TypeParamNodeView}
 * that carries its bound list and owner back-reference.
 */
public class ClassNodeView extends GraphNodeView {

    // ── Generic type parameters ───────────────────────────────────────────────

    /**
     * Formal type parameters declared on this class.
     * {@code class Foo<T, K extends Comparable<K>>} → [T-view, K-view]
     * Empty for non-generic classes.
     */
    private final List<TypeParamNodeView> typeParameters = new ArrayList<>();

    // ── Inheritance ───────────────────────────────────────────────────────────

    /** The direct superclass (EXTENDS edge target), or {@code null}. */
    private ClassNodeView superClass;

    /** Interfaces implemented / extended by this type. */
    private final List<ClassNodeView> interfaces = new ArrayList<>();

    /** Direct known subclasses (reverse EXTENDS). */
    private final List<ClassNodeView> subClasses = new ArrayList<>();

    /** Types that implement this interface (reverse IMPLEMENTS). */
    private final List<ClassNodeView> implementations = new ArrayList<>();

    // ── Declared members ─────────────────────────────────────────────────────

    private final List<MethodNodeView>      methods      = new ArrayList<>();
    private final List<ConstructorNodeView> constructors = new ArrayList<>();
    private final List<FieldNodeView>       fields       = new ArrayList<>();
    private final List<ClassNodeView>       innerClasses = new ArrayList<>();
    private final List<LambdaNodeView>      lambdas      = new ArrayList<>();

    /**
     * Attributes declared inside this {@code @interface}, if applicable.
     * Empty for regular classes.
     */
    private final List<AnnotationAttributeView> annotationAttributes = new ArrayList<>();

    // ── Usage ─────────────────────────────────────────────────────────────────

    /** Executables that create an instance of this class via {@code new}. */
    private final List<GraphNodeView> instantiatedBy = new ArrayList<>();

    /** Nodes that carry an {@code ANNOTATED_WITH} edge pointing to this type. */
    private final List<GraphNodeView> annotatedNodes = new ArrayList<>();

    ClassNodeView(GraphNode node) {
        super(node);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Generic type parameters declared on this class. */
    public List<TypeParamNodeView>      getTypeParameters()      { return typeParameters; }

    public ClassNodeView                getSuperClass()           { return superClass; }
    public List<ClassNodeView>          getInterfaces()           { return interfaces; }
    public List<ClassNodeView>          getSubClasses()           { return subClasses; }
    public List<ClassNodeView>          getImplementations()      { return implementations; }
    public List<MethodNodeView>         getMethods()              { return methods; }
    public List<ConstructorNodeView>    getConstructors()         { return constructors; }
    public List<FieldNodeView>          getFields()               { return fields; }
    public List<ClassNodeView>          getInnerClasses()         { return innerClasses; }
    public List<LambdaNodeView>         getLambdas()              { return lambdas; }
    public List<AnnotationAttributeView> getAnnotationAttributes(){ return annotationAttributes; }
    public List<GraphNodeView>          getInstantiatedBy()       { return instantiatedBy; }
    public List<GraphNodeView>          getAnnotatedNodes()       { return annotatedNodes; }

    // ── Package-private mutators used by GraphViewBuilder ─────────────────────

    void addTypeParameter(TypeParamNodeView tp)         { typeParameters.add(tp); }
    void setSuperClass(ClassNodeView superClass)        { this.superClass = superClass; }
    void addInterface(ClassNodeView iface)              { interfaces.add(iface); }
    void addSubClass(ClassNodeView sub)                 { subClasses.add(sub); }
    void addImplementation(ClassNodeView impl)          { implementations.add(impl); }
    void addMethod(MethodNodeView m)                    { methods.add(m); }
    void addConstructor(ConstructorNodeView c)          { constructors.add(c); }
    void addField(FieldNodeView f)                      { fields.add(f); }
    void addInnerClass(ClassNodeView inner)             { innerClasses.add(inner); }
    void addLambda(LambdaNodeView l)                    { lambdas.add(l); }
    void addAnnotationAttribute(AnnotationAttributeView a) { annotationAttributes.add(a); }
    void addInstantiatedBy(GraphNodeView caller)        { instantiatedBy.add(caller); }
    void addAnnotatedNode(GraphNodeView node)           { annotatedNodes.add(node); }
}
