package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a fully cross-linked {@link MarkdownGraph} from a {@link ProjectGraph}.
 *
 * <p>Renamed from {@code GraphViewBuilder} (was in {@code com.example.mrrag.graph}).
 *
 * <h3>Two-pass algorithm</h3>
 * <ol>
 *   <li>Pass 1 – create a typed {@link MarkdownNode} for every {@link GraphNode}.</li>
 *   <li>Pass 2 – wire every {@link GraphEdge} as a direct Java reference.</li>
 * </ol>
 */
@Slf4j
@Component
public class MarkdownGraphBuilder {

    public MarkdownGraph build(ProjectGraph graph) {
        log.debug("LinkedGraphBuilder: starting build ({} nodes, {} edge-sources)",
                graph.nodes.size(), graph.edgesFrom.size());

        MarkdownGraph lg = new MarkdownGraph();

        // Pass 1: create typed nodes
        for (GraphNode node : graph.nodes.values()) {
            MarkdownNode view = switch (node.kind()) {
                case CLASS             -> new MarkdownClassNode(node);
                case INTERFACE         -> new MarkdownInterfaceNode(node);
                case METHOD            -> new MarkdownMethodNode(node);
                case CONSTRUCTOR       -> new MarkdownConstructorNode(node);
                case FIELD             -> new MarkdownFieldNode(node);
                case VARIABLE          -> new MarkdownVariableNode(node);
                case LAMBDA            -> new MarkdownLambdaNode(node);
                case ANNOTATION        -> new MarkdownClassNode(node);
                case TYPE_PARAM        -> new MarkdownTypeParamNode(node);
                case ANNOTATION_ATTRIBUTE -> new MarkdownAnnotationAttribute(node);
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

    private void wireEdge(MarkdownGraph lg, ProjectGraph graph, GraphEdge edge) {
        MarkdownNode from = resolve(lg, edge.caller());
        MarkdownNode to   = resolve(lg, edge.callee());

        switch (edge.kind()) {

            case DECLARES -> {
                to.addDeclaredBy(from);
                if (from instanceof MarkdownClassNode cls) {
                    if      (to instanceof MarkdownMethodNode m)          { cls.addMethod(m);           m.setDeclaredByClass(cls); }
                    else if (to instanceof MarkdownConstructorNode c)     { cls.addConstructor(c);      c.setDeclaredByClass(cls); }
                    else if (to instanceof MarkdownFieldNode f)           { cls.addField(f);            f.setDeclaredByClass(cls); }
                    else if (to instanceof MarkdownClassNode ic)          { cls.addInnerClass(ic); }
                    else if (to instanceof MarkdownInterfaceNode ii)      { cls.addInnerInterface(ii); }
                    else if (to instanceof MarkdownLambdaNode l)          { cls.addLambda(l); }
                    else if (to instanceof MarkdownAnnotationAttribute a) { cls.addAnnotationAttribute(a); }
                } else if (from instanceof MarkdownInterfaceNode iface) {
                    if      (to instanceof MarkdownMethodNode m)          { iface.addMethod(m); }
                    else if (to instanceof MarkdownFieldNode f)           { iface.addField(f); }
                    else if (to instanceof MarkdownClassNode ic)          { iface.addInnerType(ic); }
                    else if (to instanceof MarkdownInterfaceNode ii)      { iface.addInnerType(ii); }
                }
                if (from instanceof MarkdownMethodNode m && to instanceof MarkdownLambdaNode l) {
                    m.addLambda(l); l.setDeclaredByExecutable(m);
                }
                if (from instanceof MarkdownConstructorNode c && to instanceof MarkdownLambdaNode l) {
                    c.addLambda(l); l.setDeclaredByExecutable(c);
                }
            }

            case HAS_TYPE_PARAM -> {
                if (from instanceof MarkdownClassNode cls && to instanceof MarkdownTypeParamNode tp)    { cls.addTypeParameter(tp); }
                else if (from instanceof MarkdownInterfaceNode iface && to instanceof MarkdownTypeParamNode tp) { iface.addTypeParameter(tp); }
            }

            case ANNOTATION_ATTR -> {
                if (from instanceof MarkdownClassNode cls && to instanceof MarkdownAnnotationAttribute attr) { cls.addAnnotationAttribute(attr); }
            }

            case EXTENDS -> {
                if (from instanceof MarkdownClassNode sub && to instanceof MarkdownClassNode sup)         { sub.setSuperClass(sup); sup.addSubClass(sub); }
                else if (from instanceof MarkdownInterfaceNode sub && to instanceof MarkdownInterfaceNode sup) { sub.addExtendedInterface(sup); sup.addSubInterface(sub); }
                else if (from instanceof MarkdownInterfaceNode sub)                                    { ((MarkdownClassNode) to).addSubInterface(sub); }
            }

            case IMPLEMENTS -> {
                if (from instanceof MarkdownClassNode impl) {
                    if (to instanceof MarkdownInterfaceNode iface) { impl.addInterface(iface); iface.addImplementation(impl); }
                    else if (to instanceof MarkdownClassNode iface) { impl.addInterface(iface); iface.addImplementation(impl); }
                }
            }

            case INVOKES -> addCallerCallee(from, to, edge.line());

            case INSTANTIATES -> {
                if (to instanceof MarkdownClassNode cls) {
                    cls.addInstantiatedBy(from);
                    if      (from instanceof MarkdownMethodNode m)      { m.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownConstructorNode c) { c.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownLambdaNode l)      { l.addInstantiates(cls);     addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case INSTANTIATES_ANONYMOUS -> {
                if (to instanceof MarkdownClassNode cls) {
                    cls.addAnonymouslyInstantiatedBy(from);
                    if      (from instanceof MarkdownMethodNode m)      { m.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownConstructorNode c) { c.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownLambdaNode l)      { l.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case REFERENCES_METHOD -> {
                if      (from instanceof MarkdownMethodNode m)      { m.addReferencedMethod(to);   addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownConstructorNode c) { c.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownLambdaNode l)      { l.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
            }

            case READS_FIELD -> {
                if (to instanceof MarkdownFieldNode f) {
                    f.addReadBy(from);
                    if      (from instanceof MarkdownMethodNode m)      { m.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownConstructorNode c) { c.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownLambdaNode l)      { l.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case WRITES_FIELD -> {
                if (to instanceof MarkdownFieldNode f) {
                    f.addWrittenBy(from);
                    if      (from instanceof MarkdownMethodNode m)      { m.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownConstructorNode c) { c.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof MarkdownLambdaNode l)      { l.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            case READS_LOCAL_VAR -> {
                if (to instanceof MarkdownVariableNode v) {
                    v.addReadBy(from);
                    if      (from instanceof MarkdownMethodNode m)      m.addReadsLocalVar(v);
                    else if (from instanceof MarkdownConstructorNode c) c.addReadsLocalVar(v);
                    else if (from instanceof MarkdownLambdaNode l)      l.addReadsLocalVar(v);
                }
            }

            case WRITES_LOCAL_VAR -> {
                if (to instanceof MarkdownVariableNode v) {
                    v.addWrittenBy(from);
                    if      (from instanceof MarkdownMethodNode m)      m.addWritesLocalVar(v);
                    else if (from instanceof MarkdownConstructorNode c) c.addWritesLocalVar(v);
                    else if (from instanceof MarkdownLambdaNode l)      l.addWritesLocalVar(v);
                }
            }

            case THROWS -> {
                if      (from instanceof MarkdownMethodNode m)      { m.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownConstructorNode c) { c.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownLambdaNode l)      { l.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
            }

            case ANNOTATED_WITH -> {
                if (to instanceof MarkdownClassNode annType) {
                    from.addAnnotatedBy(annType);
                    annType.addAnnotatedNode(from);
                } else {
                    MarkdownClassNode stubAnn = (MarkdownClassNode) resolve(lg, to.getId());
                    from.addAnnotatedBy(stubAnn);
                }
            }

            case REFERENCES_TYPE -> {
                if (to instanceof MarkdownClassNode cls)           cls.addReferencedBy(from);
                else if (to instanceof MarkdownInterfaceNode iface) iface.addReferencedBy(from);
                if      (from instanceof MarkdownMethodNode m)      { m.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownConstructorNode c) { c.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof MarkdownLambdaNode l)      { l.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
            }

            case OVERRIDES -> {
                if (from instanceof MarkdownMethodNode sub && to instanceof MarkdownMethodNode sup) {
                    sub.setOverrides(sup); sup.addOverriddenBy(sub);
                }
            }

            default -> { /* HAS_BOUND, etc. – not wired into linked layer */ }
        }
    }

    private void addCallerCallee(MarkdownNode from, MarkdownNode to, int line) {
        if      (from instanceof MarkdownMethodNode m)      { m.addCallee(to);   m.addEdgeLine(to.getId(), line); }
        else if (from instanceof MarkdownConstructorNode c) { c.addCallee(to);   c.addEdgeLine(to.getId(), line); }
        else if (from instanceof MarkdownLambdaNode l)      { l.addCallee(to);   l.addEdgeLine(to.getId(), line); }

        String callerId = from.getId();
        if      (to instanceof MarkdownMethodNode m)      { m.addCaller(from);      m.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof MarkdownConstructorNode c) { c.addCaller(from);      c.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof MarkdownLambdaNode l)      { l.addCaller(from);      l.recordCallerInvocationSite(callerId, line); }
    }

    private static void addEdgeLine(MarkdownNode from, String targetId, int line) {
        if (line <= 0) return;
        if      (from instanceof MarkdownMethodNode m)      m.addEdgeLine(targetId, line);
        else if (from instanceof MarkdownConstructorNode c) c.addEdgeLine(targetId, line);
        else if (from instanceof MarkdownLambdaNode l)      l.addEdgeLine(targetId, line);
    }

    private MarkdownNode resolve(MarkdownGraph lg, String id) {
        MarkdownNode existing = lg.byId(id);
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
        MarkdownNode stubView = switch (stubKind) {
            case METHOD      -> new MarkdownMethodNode(stub);
            case CONSTRUCTOR -> new MarkdownConstructorNode(stub);
            default          -> new MarkdownClassNode(stub);
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
