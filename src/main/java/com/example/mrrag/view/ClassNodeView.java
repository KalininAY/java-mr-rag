package com.example.mrrag.view;

import com.example.mrrag.service.AstGraphService.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * View for a {@code CLASS} (or interface / enum / annotation type) node.
 *
 * <h3>Generic type parameters</h3>
 * Raw generic signatures are stored as strings, e.g. {@code "E"} for
 * {@code java.util.List<E>}. The list is empty for non-generic types.
 *
 * <h3>Navigation</h3>
 * <pre>{@code
 *   ClassNodeView list = builder.classById("java.util.ArrayList");
 *   list.getSuperClass();            // ClassNodeView for AbstractList
 *   list.getInterfaces();            // [List, RandomAccess, Cloneable, …]
 *   list.getSubClasses();            // reverse EXTENDS
 *   list.getMethods();               // all MethodNodeView declared here
 *   list.getFields();                // all FieldNodeView declared here
 *   list.getConstructors();          // all ConstructorNodeView declared here
 * }</pre>
 */
public class ClassNodeView extends GraphNodeView {

    // ── Generic parameters ────────────────────────────────────────────────────
    /** Raw type-parameter names as declared, e.g. {@code ["E", "K", "V"]}. */
    private final List<String> typeParameters = new ArrayList<>();

    // ── Type hierarchy ────────────────────────────────────────────────────────
    /** The direct super-class ({@code extends}), or {@code null} for interfaces / Object. */
    private ClassNodeView superClass;
    /** All implemented interfaces ({@code implements}). */
    private final List<ClassNodeView> interfaces      = new ArrayList<>();
    /** All classes that directly extend this one (reverse {@code EXTENDS}). */
    private final List<ClassNodeView> subClasses      = new ArrayList<>();
    /** All classes that implement this interface (reverse {@code IMPLEMENTS}). */
    private final List<ClassNodeView> implementations = new ArrayList<>();

    // ── Members ───────────────────────────────────────────────────────────────
    /** Methods declared in this class. */
    private final List<MethodNodeView>      methods      = new ArrayList<>();
    /** Constructors declared in this class. */
    private final List<ConstructorNodeView> constructors = new ArrayList<>();
    /** Fields declared in this class. */
    private final List<FieldNodeView>       fields       = new ArrayList<>();
    /** Inner / anonymous types declared in this class. */
    private final List<ClassNodeView>       innerClasses = new ArrayList<>();
    /** Lambda expressions directly owned by this class (rare; usually owned by methods). */
    private final List<LambdaNodeView>      lambdas      = new ArrayList<>();

    // ── Usage ─────────────────────────────────────────────────────────────────
    /** Callables (methods / constructors) that instantiate this class. */
    private final List<GraphNodeView> instantiatedBy            = new ArrayList<>();
    /** Callables that anonymously subclass this type. */
    private final List<GraphNodeView> anonymouslyInstantiatedBy = new ArrayList<>();
    /** Nodes annotated with this annotation type (reverse {@code ANNOTATED_WITH}). */
    private final List<GraphNodeView> annotatedNodes            = new ArrayList<>();
    /** Nodes that reference this type ({@code Foo.class}, cast, instanceof …). */
    private final List<GraphNodeView> referencedBy              = new ArrayList<>();
    /** Annotation types applied to this class. */
    private final List<GraphNodeView> annotations               = new ArrayList<>();

    public ClassNodeView(GraphNode node) {
        super(node);
    }

    // ── Generic parameters ────────────────────────────────────────────────────
    public List<String>            getTypeParameters()            { return typeParameters; }

    // ── Type hierarchy ────────────────────────────────────────────────────────
    public ClassNodeView           getSuperClass()                { return superClass; }
    public List<ClassNodeView>     getInterfaces()                { return interfaces; }
    public List<ClassNodeView>     getSubClasses()                { return subClasses; }
    public List<ClassNodeView>     getImplementations()           { return implementations; }

    // ── Members ───────────────────────────────────────────────────────────────
    public List<MethodNodeView>      getMethods()                 { return methods; }
    public List<ConstructorNodeView> getConstructors()            { return constructors; }
    public List<FieldNodeView>       getFields()                  { return fields; }
    public List<ClassNodeView>       getInnerClasses()            { return innerClasses; }
    public List<LambdaNodeView>      getLambdas()                 { return lambdas; }

    // ── Usage ─────────────────────────────────────────────────────────────────
    public List<GraphNodeView>     getInstantiatedBy()            { return instantiatedBy; }
    public List<GraphNodeView>     getAnonymouslyInstantiatedBy() { return anonymouslyInstantiatedBy; }
    public List<GraphNodeView>     getAnnotatedNodes()            { return annotatedNodes; }
    public List<GraphNodeView>     getReferencedBy()              { return referencedBy; }
    public List<GraphNodeView>     getAnnotations()               { return annotations; }

    // ── Package-private mutators ──────────────────────────────────────────────
    void setSuperClass(ClassNodeView v)                { this.superClass = v; }
    void addInterface(ClassNodeView v)                 { interfaces.add(v); }
    void addSubClass(ClassNodeView v)                  { subClasses.add(v); }
    void addImplementation(ClassNodeView v)            { implementations.add(v); }
    void addMethod(MethodNodeView v)                   { methods.add(v); }
    void addConstructor(ConstructorNodeView v)         { constructors.add(v); }
    void addField(FieldNodeView v)                     { fields.add(v); }
    void addInnerClass(ClassNodeView v)                { innerClasses.add(v); }
    void addLambda(LambdaNodeView v)                   { lambdas.add(v); }
    void addInstantiatedBy(GraphNodeView v)            { instantiatedBy.add(v); }
    void addAnonymouslyInstantiatedBy(GraphNodeView v) { anonymouslyInstantiatedBy.add(v); }
    void addAnnotatedNode(GraphNodeView v)             { annotatedNodes.add(v); }
    void addReferencedBy(GraphNodeView v)              { referencedBy.add(v); }
    void addAnnotation(GraphNodeView v)                { annotations.add(v); }
    void addTypeParameter(String tp)                   { typeParameters.add(tp); }
}
