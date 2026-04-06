package com.example.mrrag.graph.linked;

import com.example.mrrag.graph.GraphRawBuilder;
import com.example.mrrag.graph.GraphRawBuilder.EdgeKind;
import com.example.mrrag.graph.GraphRawBuilder.GraphEdge;
import com.example.mrrag.graph.GraphRawBuilder.GraphNode;
import com.example.mrrag.graph.GraphRawBuilder.NodeKind;
import com.example.mrrag.graph.GraphRawBuilder.ProjectGraphRaw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a fully cross-linked {@link LinkedGraph} from a {@link ProjectGraphRaw}.
 *
 * <p>Renamed from {@code GraphViewBuilder} (was in {@code com.example.mrrag.graph}).
 *
 * <h3>Two-pass algorithm</h3>
 * <ol>
 *   <li>Pass 1 – create a typed {@link LinkedNode} for every {@link GraphNode}.</li>
 *   <li>Pass 2 – wire every {@link GraphEdge} as a direct Java reference.</li>
 * </ol>
 */
@Slf4j
@Component
public class LinkedGraphBuilder {

    public LinkedGraph build(ProjectGraphRaw graph) {
        log.debug("LinkedGraphBuilder: starting build ({} nodes, {} edge-sources)",
                graph.nodes.size(), graph.edgesFrom.size());

        LinkedGraph lg = new LinkedGraph();

        // Pass 1: create typed nodes
        for (GraphNode node : graph.nodes.values()) {
            LinkedNode view = switch (node.kind()) {
                case CLASS             -> new LinkedClassNode(node);
                case INTERFACE         -> new LinkedInterfaceNode(node);
                case METHOD            -> new LinkedMethodNode(node);
                case CONSTRUCTOR       -> new LinkedConstructorNode(node);
                case FIELD             -> new LinkedFieldNode(node);
                case VARIABLE          -> new LinkedVariableNode(node);
                case LAMBDA            -> new LinkedLambdaNode(node);
                case ANNOTATION        -> new LinkedClassNode(node);
                case TYPE_PARAM        -> new LinkedTypeParamNode(node);
                case ANNOTATION_ATTRIBUTE -> new LinkedAnnotationAttribute(node);
            };
            lg.put(view);
        }

        log.debug("LinkedGraphBuilder: pass 1 done ({} nodes)", graph.nodes.size());

        // Pass 2: wire edges
        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge edge : edges) {
                wireEdge(lg, graph, edge);
            }
        }

        log.debug("LinkedGraphBuilder: pass 2 done");
        return lg;
    }

    private void wireEdge(LinkedGraph lg, ProjectGraphRaw graph, GraphEdge edge) {
        LinkedNode from = resolve(lg, edge.caller());
        LinkedNode to   = resolve(lg, edge.callee());

        switch (edge.kind()) {

            case DECLARES -> {
                to.addDeclaredBy(from);
                if (from instanceof LinkedClassNode cls) {
                    if      (to instanceof LinkedMethodNode m)          { cls.addMethod(m);           m.setDeclaredByClass(cls); }
                    else if (to instanceof LinkedConstructorNode c)     { cls.addConstructor(c);      c.setDeclaredByClass(cls); }
                    else if (to instanceof LinkedFieldNode f)           { cls.addField(f);            f.setDeclaredByClass(cls); }
                    else if (to instanceof LinkedClassNode ic)          { cls.addInnerClass(ic); }
                    else if (to instanceof LinkedInterfaceNode ii)      { cls.addInnerInterface(ii); }
                    else if (to instanceof LinkedLambdaNode l)          { cls.addLambda(l); }
                    else if (to instanceof LinkedAnnotationAttribute a) { cls.addAnnotationAttribute(a); }
                } else if (from instanceof LinkedInterfaceNode iface) {
                    if      (to instanceof LinkedMethodNode m)          { iface.addMethod(m); }
                    else if (to instanceof LinkedFieldNode f)           { iface.addField(f); }
                    else if (to instanceof LinkedClassNode ic)          { iface.addInnerType(ic); }
                    else if (to instanceof LinkedInterfaceNode ii)      { iface.addInnerType(ii); }
                }
                if (from instanceof LinkedMethodNode m && to instanceof LinkedLambdaNode l) {
                    m.addLambda(l); l.setDeclaredByExecutable(m);
                }
                if (from instanceof LinkedConstructorNode c && to instanceof LinkedLambdaNode l) {
                    c.addLambda(l); l.setDeclaredByExecutable(c);
                }
            }

            case HAS_TYPE_PARAM -> {
                if (from instanceof LinkedClassNode cls && to instanceof LinkedTypeParamNode tp)    { cls.addTypeParameter(tp); }
                else if (from instanceof LinkedInterfaceNode iface && to instanceof LinkedTypeParamNode tp) { iface.addTypeParameter(tp); }
            }

            case ANNOTATION_ATTR -> {
                if (from instanceof LinkedClassNode cls && to instanceof LinkedAnnotationAttribute attr) { cls.addAnnotationAttribute(attr); }
            }

            case EXTENDS -> {
                if (from instanceof LinkedClassNode sub && to instanceof LinkedClassNode sup)         { sub.setSuperClass(sup); sup.addSubClass(sub); }
                else if (from instanceof LinkedInterfaceNode sub && to instanceof LinkedInterfaceNode sup) { sub.addExtendedInterface(sup); sup.addSubInterface(sub); }
                else if (from instanceof LinkedInterfaceNode sub)                                    { ((LinkedClassNode) to).addSubInterface(sub); }
            }

            case IMPLEMENTS -> {
                if (from instanceof LinkedClassNode impl) {
                    if (to instanceof LinkedInterfaceNode iface) { impl.addInterface(iface); iface.addImplementation(impl); }
                    else if (to instanceof LinkedClassNode iface) { impl.addInterface(iface); iface.addImplementation(impl); }
                }
            }

            case INVOKES -> addCallerCallee(from, to, edge.line());

            case INSTANTIATES -> {
                if (to instanceof LinkedClassNode cls) {
                    cls.addInstantiatedBy(from);
                    if      (from instanceof LinkedMethodNode m)      { m.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedConstructorNode c) { c.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedLambdaNode l)      { l.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case INSTANTIATES_ANONYMOUS -> {
                if (to instanceof LinkedClassNode cls) {
                    cls.addAnonymouslyInstantiatedBy(from);
                    if      (from instanceof LinkedMethodNode m)      { m.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedConstructorNode c) { c.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedLambdaNode l)      { l.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case REFERENCES_METHOD -> {
                if      (from instanceof LinkedMethodNode m)      { m.addReferencedMethod(to);   addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedConstructorNode c) { c.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedLambdaNode l)      { l.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
            }

            case READS_FIELD -> {
                if (to instanceof LinkedFieldNode f) {
                    f.addReadBy(from);
                    if      (from instanceof LinkedMethodNode m)      { m.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedConstructorNode c) { c.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedLambdaNode l)      { l.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case WRITES_FIELD -> {
                if (to instanceof LinkedFieldNode f) {
                    f.addWrittenBy(from);
                    if      (from instanceof LinkedMethodNode m)      { m.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedConstructorNode c) { c.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LinkedLambdaNode l)      { l.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case READS_LOCAL_VAR -> {
                if (to instanceof LinkedVariableNode v) {
                    v.addReadBy(from);
                    if      (from instanceof LinkedMethodNode m)      m.addReadsLocalVar(v);
                    else if (from instanceof LinkedConstructorNode c) c.addReadsLocalVar(v);
                    else if (from instanceof LinkedLambdaNode l)      l.addReadsLocalVar(v);
                }
            }

            case WRITES_LOCAL_VAR -> {
                if (to instanceof LinkedVariableNode v) {
                    v.addWrittenBy(from);
                    if      (from instanceof LinkedMethodNode m)      m.addWritesLocalVar(v);
                    else if (from instanceof LinkedConstructorNode c) c.addWritesLocalVar(v);
                    else if (from instanceof LinkedLambdaNode l)      l.addWritesLocalVar(v);
                }
            }

            case THROWS -> {
                if      (from instanceof LinkedMethodNode m)      { m.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedConstructorNode c) { c.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedLambdaNode l)      { l.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
            }

            case ANNOTATED_WITH -> {
                if (to instanceof LinkedClassNode annType) {
                    from.addAnnotatedBy(annType);
                    annType.addAnnotatedNode(from);
                } else {
                    LinkedClassNode stubAnn = (LinkedClassNode) resolve(lg, to.getId());
                    from.addAnnotatedBy(stubAnn);
                }
            }

            case REFERENCES_TYPE -> {
                if (to instanceof LinkedClassNode cls)           cls.addReferencedBy(from);
                else if (to instanceof LinkedInterfaceNode iface) iface.addReferencedBy(from);
                if      (from instanceof LinkedMethodNode m)      { m.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedConstructorNode c) { c.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LinkedLambdaNode l)      { l.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
            }

            case OVERRIDES -> {
                if (from instanceof LinkedMethodNode sub && to instanceof LinkedMethodNode sup) {
                    sub.setOverrides(sup); sup.addOverriddenBy(sub);
                }
            }

            default -> { /* HAS_BOUND, etc. – not wired into linked layer */ }
        }
    }

    private void addCallerCallee(LinkedNode from, LinkedNode to, int line) {
        if      (from instanceof LinkedMethodNode m)      { m.addCallee(to);   m.addEdgeLine(to.getId(), line); }
        else if (from instanceof LinkedConstructorNode c) { c.addCallee(to);   c.addEdgeLine(to.getId(), line); }
        else if (from instanceof LinkedLambdaNode l)      { l.addCallee(to);   l.addEdgeLine(to.getId(), line); }

        String callerId = from.getId();
        if      (to instanceof LinkedMethodNode m)      { m.addCaller(from);      m.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof LinkedConstructorNode c) { c.addCaller(from);      c.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof LinkedLambdaNode l)      { l.addCaller(from);      l.recordCallerInvocationSite(callerId, line); }
    }

    private static void addEdgeLine(LinkedNode from, String targetId, int line) {
        if (line <= 0) return;
        if      (from instanceof LinkedMethodNode m)      m.addEdgeLine(targetId, line);
        else if (from instanceof LinkedConstructorNode c) c.addEdgeLine(targetId, line);
        else if (from instanceof LinkedLambdaNode l)      l.addEdgeLine(targetId, line);
    }

    private LinkedNode resolve(LinkedGraph lg, String id) {
        LinkedNode existing = lg.byId(id);
        if (existing != null) return existing;

        boolean hasHash  = id != null && id.contains("#");
        boolean hasParen = id != null && id.contains("(");

        final NodeKind stubKind;
        if (hasHash && hasParen) {
            stubKind = isConstructorId(id) ? NodeKind.CONSTRUCTOR : NodeKind.METHOD;
        } else if (hasHash) {
            stubKind = NodeKind.CONSTRUCTOR;
        } else {
            stubKind = NodeKind.CLASS;
        }

        GraphNode stub = new GraphNode(id, stubKind, simpleNameOf(id), "external", -1, -1, "", "");
        LinkedNode stubView = switch (stubKind) {
            case METHOD      -> new LinkedMethodNode(stub);
            case CONSTRUCTOR -> new LinkedConstructorNode(stub);
            default          -> new LinkedClassNode(stub);
        };
        lg.put(stubView);
        log.trace("LinkedGraphBuilder: stub {} for '{}'", stubKind, id);
        return stubView;
    }

    private static boolean isConstructorId(String id) {
        int hash = id.indexOf('#');
        if (hash < 0) return false;
        String afterHash = id.substring(hash + 1);
        if (afterHash.startsWith("<init>(")) return true;
        String owner = id.substring(0, hash);
        int dot = owner.lastIndexOf('.');
        String simpleName = dot >= 0 ? owner.substring(dot + 1) : owner;
        int dollar = simpleName.lastIndexOf('$');
        if (dollar >= 0) simpleName = simpleName.substring(dollar + 1);
        if (afterHash.startsWith(simpleName + "(")) return true;
        int open = afterHash.indexOf('(');
        if (open > 0 && afterHash.lastIndexOf(')') == afterHash.length() - 1) {
            String nameBeforeParams = afterHash.substring(0, open);
            return nameBeforeParams.equals(simpleName) || nameBeforeParams.endsWith("." + simpleName);
        }
        return false;
    }

    private static String simpleNameOf(String id) {
        if (id == null || id.isBlank()) return "?";
        int hash = id.lastIndexOf('#');
        if (hash >= 0) return id.substring(hash + 1);
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }
}
