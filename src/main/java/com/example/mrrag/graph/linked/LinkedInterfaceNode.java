package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for an INTERFACE element.
 * Renamed from {@code InterfaceNodeView}.
 */
public class LinkedInterfaceNode extends LinkedNode {

    private final List<LinkedTypeParamNode>  typeParameters    = new ArrayList<>();
    private final List<LinkedInterfaceNode>  extendedInterfaces = new ArrayList<>();
    private final List<LinkedInterfaceNode>  subInterfaces     = new ArrayList<>();
    private final List<LinkedClassNode>      implementations   = new ArrayList<>();
    private final List<LinkedMethodNode>     methods           = new ArrayList<>();
    private final List<LinkedFieldNode>      fields            = new ArrayList<>();
    private final List<LinkedNode>           innerTypes        = new ArrayList<>();
    private final List<LinkedNode>           referencedBy      = new ArrayList<>();
    private final List<LinkedNode>           annotatedNodes    = new ArrayList<>();

    public LinkedInterfaceNode(GraphNode node) { super(node); }

    public List<LinkedTypeParamNode>  getTypeParameters()    { return typeParameters; }
    public List<LinkedInterfaceNode>  getExtendedInterfaces() { return extendedInterfaces; }
    public List<LinkedInterfaceNode>  getSubInterfaces()     { return subInterfaces; }
    public List<LinkedClassNode>      getImplementations()   { return implementations; }
    public List<LinkedMethodNode>     getMethods()           { return methods; }
    public List<LinkedFieldNode>      getFields()            { return fields; }
    public List<LinkedNode>           getInnerTypes()        { return innerTypes; }
    public List<LinkedNode>           getReferencedBy()      { return referencedBy; }
    public List<LinkedNode>           getAnnotatedNodes()    { return annotatedNodes; }

    public void addTypeParameter(LinkedTypeParamNode tp)    { typeParameters.add(tp); }
    public void addExtendedInterface(LinkedInterfaceNode i) { extendedInterfaces.add(i); }
    public void addSubInterface(LinkedInterfaceNode i)      { subInterfaces.add(i); }
    public void addImplementation(LinkedClassNode c)        { implementations.add(c); }
    public void addMethod(LinkedMethodNode m)               { methods.add(m); }
    public void addField(LinkedFieldNode f)                 { fields.add(f); }
    public void addInnerType(LinkedNode t)                  { innerTypes.add(t); }
    public void addReferencedBy(LinkedNode v)               { referencedBy.add(v); }
    public void addAnnotatedNode(LinkedNode v)              { annotatedNodes.add(v); }
}
