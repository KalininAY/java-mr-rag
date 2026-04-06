package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for a CLASS (or ANNOTATION) element.
 * Renamed from {@code ClassNodeView}.
 */
public class LinkedClassNode extends LinkedNode {

    private final List<LinkedTypeParamNode>        typeParameters            = new ArrayList<>();
    private LinkedClassNode                        superClass;
    private final List<LinkedClassNode>            interfaces                = new ArrayList<>();
    private final List<LinkedInterfaceNode>        implementedInterfaces     = new ArrayList<>();
    private final List<LinkedClassNode>            subClasses                = new ArrayList<>();
    private final List<LinkedInterfaceNode>        subInterfaces             = new ArrayList<>();
    private final List<LinkedClassNode>            implementations           = new ArrayList<>();
    private final List<LinkedMethodNode>           methods                   = new ArrayList<>();
    private final List<LinkedConstructorNode>      constructors              = new ArrayList<>();
    private final List<LinkedFieldNode>            fields                    = new ArrayList<>();
    private final List<LinkedClassNode>            innerClasses              = new ArrayList<>();
    private final List<LinkedInterfaceNode>        innerInterfaces           = new ArrayList<>();
    private final List<LinkedLambdaNode>           lambdas                   = new ArrayList<>();
    private final List<LinkedAnnotationAttribute>  annotationAttributes      = new ArrayList<>();
    private final List<LinkedNode>                 instantiatedBy            = new ArrayList<>();
    private final List<LinkedNode>                 anonymouslyInstantiatedBy = new ArrayList<>();
    private final List<LinkedNode>                 referencedBy              = new ArrayList<>();
    private final List<LinkedNode>                 annotatedNodes            = new ArrayList<>();

    public LinkedClassNode(GraphNode node) { super(node); }

    // Accessors
    public List<LinkedTypeParamNode>       getTypeParameters()            { return typeParameters; }
    public LinkedClassNode                 getSuperClass()                { return superClass; }
    public List<LinkedClassNode>           getInterfaces()                { return interfaces; }
    public List<LinkedInterfaceNode>       getImplementedInterfaces()     { return implementedInterfaces; }
    public List<LinkedClassNode>           getSubClasses()                { return subClasses; }
    public List<LinkedInterfaceNode>       getSubInterfaces()             { return subInterfaces; }
    public List<LinkedClassNode>           getImplementations()           { return implementations; }
    public List<LinkedMethodNode>          getMethods()                   { return methods; }
    public List<LinkedConstructorNode>     getConstructors()              { return constructors; }
    public List<LinkedFieldNode>           getFields()                    { return fields; }
    public List<LinkedClassNode>           getInnerClasses()              { return innerClasses; }
    public List<LinkedInterfaceNode>       getInnerInterfaces()           { return innerInterfaces; }
    public List<LinkedLambdaNode>          getLambdas()                   { return lambdas; }
    public List<LinkedAnnotationAttribute> getAnnotationAttributes()      { return annotationAttributes; }
    public List<LinkedNode>                getInstantiatedBy()            { return instantiatedBy; }
    public List<LinkedNode>                getAnonymouslyInstantiatedBy() { return anonymouslyInstantiatedBy; }
    public List<LinkedNode>                getReferencedBy()              { return referencedBy; }
    public List<LinkedNode>                getAnnotatedNodes()            { return annotatedNodes; }

    // Mutators
    public void addTypeParameter(LinkedTypeParamNode tp)           { typeParameters.add(tp); }
    public void setSuperClass(LinkedClassNode sc)                  { this.superClass = sc; }
    public void addInterface(LinkedClassNode i)                    { interfaces.add(i); }
    public void addInterface(LinkedInterfaceNode i)                { implementedInterfaces.add(i); }
    public void addSubClass(LinkedClassNode s)                     { subClasses.add(s); }
    public void addSubInterface(LinkedInterfaceNode s)             { subInterfaces.add(s); }
    public void addImplementation(LinkedClassNode impl)            { implementations.add(impl); }
    public void addMethod(LinkedMethodNode m)                      { methods.add(m); }
    public void addConstructor(LinkedConstructorNode c)            { constructors.add(c); }
    public void addField(LinkedFieldNode f)                        { fields.add(f); }
    public void addInnerClass(LinkedClassNode inner)               { innerClasses.add(inner); }
    public void addInnerInterface(LinkedInterfaceNode inner)       { innerInterfaces.add(inner); }
    public void addLambda(LinkedLambdaNode l)                      { lambdas.add(l); }
    public void addAnnotationAttribute(LinkedAnnotationAttribute a){ annotationAttributes.add(a); }
    public void addInstantiatedBy(LinkedNode v)                    { instantiatedBy.add(v); }
    public void addAnonymouslyInstantiatedBy(LinkedNode v)         { anonymouslyInstantiatedBy.add(v); }
    public void addReferencedBy(LinkedNode v)                      { referencedBy.add(v); }
    public void addAnnotatedNode(LinkedNode v)                     { annotatedNodes.add(v); }
}
