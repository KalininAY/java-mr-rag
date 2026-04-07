package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.GraphNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked node for an INTERFACE element.
 * Renamed from {@code InterfaceNodeView}.
 */
public class MarkdownInterfaceNode extends MarkdownNode {

    private final List<MarkdownTypeParamNode>  typeParameters    = new ArrayList<>();
    private final List<MarkdownInterfaceNode>  extendedInterfaces = new ArrayList<>();
    private final List<MarkdownInterfaceNode>  subInterfaces     = new ArrayList<>();
    private final List<MarkdownClassNode>      implementations   = new ArrayList<>();
    private final List<MarkdownMethodNode>     methods           = new ArrayList<>();
    private final List<MarkdownFieldNode>      fields            = new ArrayList<>();
    private final List<MarkdownNode>           innerTypes        = new ArrayList<>();
    private final List<MarkdownNode>           referencedBy      = new ArrayList<>();
    private final List<MarkdownNode>           annotatedNodes    = new ArrayList<>();

    public MarkdownInterfaceNode(GraphNode node) { super(node); }

    public List<MarkdownTypeParamNode>  getTypeParameters()    { return typeParameters; }
    public List<MarkdownInterfaceNode>  getExtendedInterfaces() { return extendedInterfaces; }
    public List<MarkdownInterfaceNode>  getSubInterfaces()     { return subInterfaces; }
    public List<MarkdownClassNode>      getImplementations()   { return implementations; }
    public List<MarkdownMethodNode>     getMethods()           { return methods; }
    public List<MarkdownFieldNode>      getFields()            { return fields; }
    public List<MarkdownNode>           getInnerTypes()        { return innerTypes; }
    public List<MarkdownNode>           getReferencedBy()      { return referencedBy; }
    public List<MarkdownNode>           getAnnotatedNodes()    { return annotatedNodes; }

    public void addTypeParameter(MarkdownTypeParamNode tp)    { typeParameters.add(tp); }
    public void addExtendedInterface(MarkdownInterfaceNode i) { extendedInterfaces.add(i); }
    public void addSubInterface(MarkdownInterfaceNode i)      { subInterfaces.add(i); }
    public void addImplementation(MarkdownClassNode c)        { implementations.add(c); }
    public void addMethod(MarkdownMethodNode m)               { methods.add(m); }
    public void addField(MarkdownFieldNode f)                 { fields.add(f); }
    public void addInnerType(MarkdownNode t)                  { innerTypes.add(t); }
    public void addReferencedBy(MarkdownNode v)               { referencedBy.add(v); }
    public void addAnnotatedNode(MarkdownNode v)              { annotatedNodes.add(v); }
}
