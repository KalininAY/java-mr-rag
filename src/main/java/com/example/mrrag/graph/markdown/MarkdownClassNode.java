package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a CLASS (or ANNOTATION) element.
 * Renamed from {@code ClassNodeView}.
 */
public class MarkdownClassNode extends MarkdownNode {

    private final List<MarkdownTypeParamNode>        typeParameters            = new ArrayList<>();
    private MarkdownClassNode superClass;
    private final List<MarkdownClassNode>            interfaces                = new ArrayList<>();
    private final List<MarkdownInterfaceNode>        implementedInterfaces     = new ArrayList<>();
    private final List<MarkdownClassNode>            subClasses                = new ArrayList<>();
    private final List<MarkdownInterfaceNode>        subInterfaces             = new ArrayList<>();
    private final List<MarkdownClassNode>            implementations           = new ArrayList<>();
    private final List<MarkdownMethodNode>           methods                   = new ArrayList<>();
    private final List<MarkdownConstructorNode>      constructors              = new ArrayList<>();
    private final List<MarkdownFieldNode>            fields                    = new ArrayList<>();
    private final List<MarkdownClassNode>            innerClasses              = new ArrayList<>();
    private final List<MarkdownInterfaceNode>        innerInterfaces           = new ArrayList<>();
    private final List<MarkdownLambdaNode>           lambdas                   = new ArrayList<>();
    private final List<MarkdownAnnotationAttribute>  annotationAttributes      = new ArrayList<>();
    private final List<MarkdownNode>                 instantiatedBy            = new ArrayList<>();
    private final List<MarkdownNode>                 anonymouslyInstantiatedBy = new ArrayList<>();
    private final List<MarkdownNode>                 referencedBy              = new ArrayList<>();
    private final List<MarkdownNode>                 annotatedNodes            = new ArrayList<>();

    public MarkdownClassNode(GraphNode node) { super(node); }

    // Accessors
    public List<MarkdownTypeParamNode>       getTypeParameters()            { return typeParameters; }
    public MarkdownClassNode getSuperClass()                { return superClass; }
    public List<MarkdownClassNode>           getInterfaces()                { return interfaces; }
    public List<MarkdownInterfaceNode>       getImplementedInterfaces()     { return implementedInterfaces; }
    public List<MarkdownClassNode>           getSubClasses()                { return subClasses; }
    public List<MarkdownInterfaceNode>       getSubInterfaces()             { return subInterfaces; }
    public List<MarkdownClassNode>           getImplementations()           { return implementations; }
    public List<MarkdownMethodNode>          getMethods()                   { return methods; }
    public List<MarkdownConstructorNode>     getConstructors()              { return constructors; }
    public List<MarkdownFieldNode>           getFields()                    { return fields; }
    public List<MarkdownClassNode>           getInnerClasses()              { return innerClasses; }
    public List<MarkdownInterfaceNode>       getInnerInterfaces()           { return innerInterfaces; }
    public List<MarkdownLambdaNode>          getLambdas()                   { return lambdas; }
    public List<MarkdownAnnotationAttribute> getAnnotationAttributes()      { return annotationAttributes; }
    public List<MarkdownNode>                getInstantiatedBy()            { return instantiatedBy; }
    public List<MarkdownNode>                getAnonymouslyInstantiatedBy() { return anonymouslyInstantiatedBy; }
    public List<MarkdownNode>                getReferencedBy()              { return referencedBy; }
    public List<MarkdownNode>                getAnnotatedNodes()            { return annotatedNodes; }

    // Mutators
    public void addTypeParameter(MarkdownTypeParamNode tp)           { typeParameters.add(tp); }
    public void setSuperClass(MarkdownClassNode sc)                  { this.superClass = sc; }
    public void addInterface(MarkdownClassNode i)                    { interfaces.add(i); }
    public void addInterface(MarkdownInterfaceNode i)                { implementedInterfaces.add(i); }
    public void addSubClass(MarkdownClassNode s)                     { subClasses.add(s); }
    public void addSubInterface(MarkdownInterfaceNode s)             { subInterfaces.add(s); }
    public void addImplementation(MarkdownClassNode impl)            { implementations.add(impl); }
    public void addMethod(MarkdownMethodNode m)                      { methods.add(m); }
    public void addConstructor(MarkdownConstructorNode c)            { constructors.add(c); }
    public void addField(MarkdownFieldNode f)                        { fields.add(f); }
    public void addInnerClass(MarkdownClassNode inner)               { innerClasses.add(inner); }
    public void addInnerInterface(MarkdownInterfaceNode inner)       { innerInterfaces.add(inner); }
    public void addLambda(MarkdownLambdaNode l)                      { lambdas.add(l); }
    public void addAnnotationAttribute(MarkdownAnnotationAttribute a){ annotationAttributes.add(a); }
    public void addInstantiatedBy(MarkdownNode v)                    { instantiatedBy.add(v); }
    public void addAnonymouslyInstantiatedBy(MarkdownNode v)         { anonymouslyInstantiatedBy.add(v); }
    public void addReferencedBy(MarkdownNode v)                      { referencedBy.add(v); }
    public void addAnnotatedNode(MarkdownNode v)                     { annotatedNodes.add(v); }
}
