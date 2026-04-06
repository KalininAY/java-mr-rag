package com.example.mrrag.graph;

import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.view.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds a fully cross-linked view graph from a {@link ProjectGraph}.
 *
 * <h3>Two-pass algorithm</h3>
 * <ol>
 *   <li><b>Pass 1 – instantiate views.</b>  For every {@link GraphNode} in the
 *       graph one typed view ({@link ClassNodeView}, {@link InterfaceNodeView},
 *       {@link MethodNodeView}, …) is created and stored in an id→view map.</li>
 *   <li><b>Pass 2 – wire edges.</b>  Every {@link GraphEdge} is translated into
 *       a direct Java reference on both the source and the target view.</li>
 * </ol>
 *
 * <p>After {@link #build(ProjectGraph)} returns, any view can be used as the
 * entry-point for a full graph traversal.
 *
 * <h3>Unknown / external nodes</h3>
 * Target IDs that have no corresponding {@link GraphNode} in the graph are
 * represented by lightweight "stub" views whose type is inferred from the id
 * format:
 * <ul>
 *   <li>ids of the form {@code pkg.ClassName#ClassName(params)} or
 *       {@code pkg.ClassName#<init>(params)} → {@link ConstructorNodeView} stub</li>
 *   <li>ids containing {@code #} and {@code (} but <em>not</em> matching the
 *       constructor pattern → {@link MethodNodeView} stub</li>
 *   <li>ids containing {@code #} but <em>no</em> {@code (} → {@link ConstructorNodeView} stub
 *       (legacy / no-arg constructor reference)</li>
 *   <li>all other ids → {@link ClassNodeView} stub</li>
 * </ul>
 * Stub nodes always have {@code startLine = -1}, {@code filePath = "external"},
 * and an empty snippet, so {@link com.example.mrrag.view.GraphNodeView#toMarkdown()}
 * correctly emits {@code -1|<id>} lines for them.
 */
@Slf4j
@Component
public class GraphViewBuilder {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Container returned by {@link #build(GraphRawBuilder.ProjectGraphRaw)}.
     * Provides typed look-up methods by node id or simple name.
     */
    public static class ViewGraph {

        private final Map<String, GraphNodeView>       byId   = new LinkedHashMap<>();
        private final Map<String, List<GraphNodeView>> byName = new LinkedHashMap<>();

        void put(GraphNodeView v) {
            byId.put(v.getId(), v);
            byName.computeIfAbsent(v.getSimpleName(), k -> new ArrayList<>()).add(v);
        }

        /** All views, in insertion order. */
        public Collection<GraphNodeView> all() {
            return Collections.unmodifiableCollection(byId.values());
        }

        /** Look up a view by its exact node id, or {@code null} if not found. */
        public GraphNodeView byId(String id) { return byId.get(id); }

        /** All views whose {@link GraphNodeView#getSimpleName()} matches. */
        public List<GraphNodeView> bySimpleName(String name) {
            return byName.getOrDefault(name, List.of());
        }

        /** Convenience cast to {@link ClassNodeView}, or {@code null}. */
        public ClassNodeView classById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof ClassNodeView c ? c : null;
        }

        /** Convenience cast to {@link InterfaceNodeView}, or {@code null}. */
        public InterfaceNodeView interfaceById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof InterfaceNodeView i ? i : null;
        }

        /** Convenience cast to {@link MethodNodeView}, or {@code null}. */
        public MethodNodeView methodById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof MethodNodeView m ? m : null;
        }

        /** Convenience cast to {@link ConstructorNodeView}, or {@code null}. */
        public ConstructorNodeView constructorById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof ConstructorNodeView c ? c : null;
        }

        /** Convenience cast to {@link FieldNodeView}, or {@code null}. */
        public FieldNodeView fieldById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof FieldNodeView f ? f : null;
        }

        /** Convenience cast to {@link VariableNodeView}, or {@code null}. */
        public VariableNodeView variableById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof VariableNodeView vv ? vv : null;
        }

        /** Convenience cast to {@link LambdaNodeView}, or {@code null}. */
        public LambdaNodeView lambdaById(String id) {
            GraphNodeView v = byId.get(id);
            return v instanceof LambdaNodeView l ? l : null;
        }

        /** All {@link ClassNodeView} instances (concrete classes only). */
        public List<ClassNodeView> allClasses() {
            return byId.values().stream()
                    .filter(v -> v instanceof ClassNodeView)
                    .map(v -> (ClassNodeView) v)
                    .toList();
        }

        /** All {@link InterfaceNodeView} instances. */
        public List<InterfaceNodeView> allInterfaces() {
            return byId.values().stream()
                    .filter(v -> v instanceof InterfaceNodeView)
                    .map(v -> (InterfaceNodeView) v)
                    .toList();
        }

        /** All {@link MethodNodeView} instances in the graph. */
        public List<MethodNodeView> allMethods() {
            return byId.values().stream()
                    .filter(v -> v instanceof MethodNodeView)
                    .map(v -> (MethodNodeView) v)
                    .toList();
        }
    }

    /**
     * Converts the raw {@link ProjectGraph} into a fully cross-linked
     * {@link ViewGraph}.
     *
     * @param graph the graph produced by {@link AstGraphService#buildGraph}
     * @return a new {@link ViewGraph}; always non-null
     */
    public ViewGraph build(ProjectGraph graph) {
        log.debug("GraphViewBuilder: starting build ({} nodes, {} edge-sources)",
                graph.nodes.size(), graph.edgesFrom.size());

        ViewGraph vg = new ViewGraph();

        // ── Pass 1: create a typed view for every node ─────────────────────────
        for (GraphNode node : graph.nodes.values()) {
            GraphNodeView view = switch (node.kind()) {
                case CLASS             -> new ClassNodeView(node);
                case INTERFACE         -> new InterfaceNodeView(node);
                case METHOD            -> new MethodNodeView(node);
                case CONSTRUCTOR       -> new ConstructorNodeView(node);
                case FIELD             -> new FieldNodeView(node);
                case VARIABLE          -> new VariableNodeView(node);
                case LAMBDA            -> new LambdaNodeView(node);
                case ANNOTATION        -> new ClassNodeView(node);   // reuses ClassNodeView (has annotatedNodes)
                case TYPE_PARAM        -> new TypeParamNodeView(node);
                case ANNOTATION_ATTRIBUTE -> new AnnotationAttributeView(node);
            };
            vg.put(view);
        }

        log.debug("GraphViewBuilder: pass 1 done ({} views)", vg.byId.size());

        // ── Pass 2: wire every edge ────────────────────────────────────────────
        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge edge : edges) {
                wireEdge(vg, graph, edge);
            }
        }

        log.debug("GraphViewBuilder: pass 2 done");
        return vg;
    }

    // ------------------------------------------------------------------
    // Edge wiring
    // ------------------------------------------------------------------

    private void wireEdge(ViewGraph vg, ProjectGraph graph, GraphEdge edge) {
        GraphNodeView from = resolve(vg, edge.caller());
        GraphNodeView to   = resolve(vg, edge.callee());

        switch (edge.kind()) {

            // ── Structural ────────────────────────────────────────────────────
            case DECLARES -> {
                // from = owner (class/interface/executable), to = member
                to.addDeclaredBy(from);
                if (from instanceof ClassNodeView cls) {
                    if      (to instanceof MethodNodeView m)          { cls.addMethod(m);           m.setDeclaredByClass(cls); }
                    else if (to instanceof ConstructorNodeView c)     { cls.addConstructor(c);      c.setDeclaredByClass(cls); }
                    else if (to instanceof FieldNodeView f)           { cls.addField(f);            f.setDeclaredByClass(cls); }
                    else if (to instanceof ClassNodeView ic)          { cls.addInnerClass(ic); }
                    else if (to instanceof InterfaceNodeView ii)      { cls.addInnerInterface(ii); }
                    else if (to instanceof LambdaNodeView l)          { cls.addLambda(l); }
                    else if (to instanceof AnnotationAttributeView a) { cls.addAnnotationAttribute(a); }
                } else if (from instanceof InterfaceNodeView iface) {
                    if      (to instanceof MethodNodeView m)      { iface.addMethod(m);    m.setDeclaredByClass(null); }
                    else if (to instanceof FieldNodeView f)       { iface.addField(f);     f.setDeclaredByClass(null); }
                    else if (to instanceof ClassNodeView ic)      { iface.addInnerType(ic); }
                    else if (to instanceof InterfaceNodeView ii)  { iface.addInnerType(ii); }
                }
                if (from instanceof MethodNodeView m && to instanceof LambdaNodeView l) {
                    m.addLambda(l); l.setDeclaredByExecutable(m);
                }
                if (from instanceof ConstructorNodeView c && to instanceof LambdaNodeView l) {
                    c.addLambda(l); l.setDeclaredByExecutable(c);
                }
            }

            // ── Generic type parameters ───────────────────────────────────────
            case HAS_TYPE_PARAM -> {
                if (from instanceof ClassNodeView cls && to instanceof TypeParamNodeView tp) {
                    cls.addTypeParameter(tp);
                } else if (from instanceof InterfaceNodeView iface && to instanceof TypeParamNodeView tp) {
                    iface.addTypeParameter(tp);
                }
            }

            // ── Annotation attributes ─────────────────────────────────────────
            case ANNOTATION_ATTR -> {
                if (from instanceof ClassNodeView cls && to instanceof AnnotationAttributeView attr) {
                    cls.addAnnotationAttribute(attr);
                }
            }

            // ── Type hierarchy ────────────────────────────────────────────────
            case EXTENDS -> {
                if (from instanceof ClassNodeView sub && to instanceof ClassNodeView sup) {
                    sub.setSuperClass(sup);
                    sup.addSubClass(sub);
                } else if (from instanceof InterfaceNodeView sub && to instanceof InterfaceNodeView sup) {
                    sub.addExtendedInterface(sup);
                    sup.addSubInterface(sub);
                } else if (from instanceof InterfaceNodeView sub) {
                    ClassNodeView stubSup = (ClassNodeView) to;
                    stubSup.addSubInterface(sub);
                }
            }
            case IMPLEMENTS -> {
                if (from instanceof ClassNodeView impl) {
                    if (to instanceof InterfaceNodeView iface) {
                        impl.addInterface(iface);
                        iface.addImplementation(impl);
                    } else if (to instanceof ClassNodeView iface) {
                        impl.addInterface(iface);
                        iface.addImplementation(impl);
                    }
                }
            }

            // ── Invocations ───────────────────────────────────────────────────
            case INVOKES -> addCallerCallee(from, to, edge.line());

            case INSTANTIATES -> {
                if (to instanceof ClassNodeView cls) {
                    cls.addInstantiatedBy(from);
                    if      (from instanceof MethodNodeView m)      { m.addInstantiates(cls);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof ConstructorNodeView c) { c.addInstantiates(cls);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LambdaNodeView l)      { l.addInstantiates(cls);      addEdgeLine(from, to.getId(), edge.line()); }
                }
            }
            case INSTANTIATES_ANONYMOUS -> {
                if (to instanceof ClassNodeView cls) {
                    cls.addAnonymouslyInstantiatedBy(from);
                    if      (from instanceof MethodNodeView m)      { m.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof ConstructorNodeView c) { c.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LambdaNodeView l)      { l.addInstantiatesAnon(cls);  addEdgeLine(from, to.getId(), edge.line()); }
                }
            }
            case REFERENCES_METHOD -> {
                if      (from instanceof MethodNodeView m)      { m.addReferencedMethod(to);   addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof ConstructorNodeView c) { c.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LambdaNodeView l)      { l.addCallee(to);             addEdgeLine(from, to.getId(), edge.line()); }
            }

            // ── Field access ──────────────────────────────────────────────────
            case READS_FIELD -> {
                if (to instanceof FieldNodeView f) {
                    f.addReadBy(from);
                    if      (from instanceof MethodNodeView m)      { m.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof ConstructorNodeView c) { c.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LambdaNodeView l)      { l.addReadsField(f);       addEdgeLine(from, to.getId(), edge.line()); }
                }
            }
            case WRITES_FIELD -> {
                if (to instanceof FieldNodeView f) {
                    f.addWrittenBy(from);
                    if      (from instanceof MethodNodeView m)      { m.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof ConstructorNodeView c) { c.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                    else if (from instanceof LambdaNodeView l)      { l.addWritesField(f);      addEdgeLine(from, to.getId(), edge.line()); }
                }
            }

            // ── Local variable access ─────────────────────────────────────────
            case READS_LOCAL_VAR -> {
                if (to instanceof VariableNodeView v) {
                    v.addReadBy(from);
                    if      (from instanceof MethodNodeView m)      m.addReadsLocalVar(v);
                    else if (from instanceof ConstructorNodeView c) c.addReadsLocalVar(v);
                    else if (from instanceof LambdaNodeView l)      l.addReadsLocalVar(v);
                }
            }
            case WRITES_LOCAL_VAR -> {
                if (to instanceof VariableNodeView v) {
                    v.addWrittenBy(from);
                    if      (from instanceof MethodNodeView m)      m.addWritesLocalVar(v);
                    else if (from instanceof ConstructorNodeView c) c.addWritesLocalVar(v);
                    else if (from instanceof LambdaNodeView l)      l.addWritesLocalVar(v);
                }
            }

            // ── Exceptions ────────────────────────────────────────────────────
            case THROWS -> {
                if      (from instanceof MethodNodeView m)      { m.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof ConstructorNodeView c) { c.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LambdaNodeView l)      { l.addThrowsType(to);        addEdgeLine(from, to.getId(), edge.line()); }
            }

            // ── Annotations ───────────────────────────────────────────────────
            case ANNOTATED_WITH -> {
                if (to instanceof ClassNodeView annType) {
                    from.addAnnotatedBy(annType);
                    annType.addAnnotatedNode(from);
                } else {
                    ClassNodeView stubAnn = (ClassNodeView) resolve(vg, to.getId());
                    from.addAnnotatedBy(stubAnn);
                }
            }

            // ── Type references ───────────────────────────────────────────────
            case REFERENCES_TYPE -> {
                if (to instanceof ClassNodeView cls)            cls.addReferencedBy(from);
                else if (to instanceof InterfaceNodeView iface) iface.addReferencedBy(from);
                if      (from instanceof MethodNodeView m)      { m.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof ConstructorNodeView c) { c.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
                else if (from instanceof LambdaNodeView l)      { l.addReferencesType(to);    addEdgeLine(from, to.getId(), edge.line()); }
            }

            // ── Overrides ─────────────────────────────────────────────────────
            case OVERRIDES -> {
                if (from instanceof MethodNodeView sub && to instanceof MethodNodeView sup) {
                    sub.setOverrides(sup);
                    sup.addOverriddenBy(sub);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helper: INVOKES wiring
    // ------------------------------------------------------------------

    private void addCallerCallee(GraphNodeView from, GraphNodeView to, int line) {
        if      (from instanceof MethodNodeView m)      { m.addCallee(to);   m.addEdgeLine(to.getId(), line); }
        else if (from instanceof ConstructorNodeView c) { c.addCallee(to);   c.addEdgeLine(to.getId(), line); }
        else if (from instanceof LambdaNodeView l)      { l.addCallee(to);   l.addEdgeLine(to.getId(), line); }

        String callerId = from.getId();
        if      (to instanceof MethodNodeView m)        { m.addCaller(from);        m.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof ConstructorNodeView c)   { c.addCaller(from);        c.recordCallerInvocationSite(callerId, line); }
        else if (to instanceof LambdaNodeView l)        { l.addCaller(from);        l.recordCallerInvocationSite(callerId, line); }
    }

    /**
     * Records the source line of an outgoing edge from {@code from} to
     * {@code targetId} on views that support {@code addEdgeLine}.
     * Only {@link MethodNodeView}, {@link ConstructorNodeView}, and
     * {@link LambdaNodeView} currently expose this method.
     */
    private static void addEdgeLine(GraphNodeView from, String targetId, int line) {
        if (line <= 0) return;
        if      (from instanceof MethodNodeView m)      m.addEdgeLine(targetId, line);
        else if (from instanceof ConstructorNodeView c) c.addEdgeLine(targetId, line);
        else if (from instanceof LambdaNodeView l)      l.addEdgeLine(targetId, line);
    }

    // ------------------------------------------------------------------
    // Helper: typed stub creation for external / unresolved nodes
    // ------------------------------------------------------------------

    /**
     * Resolves a node id to an existing view, or creates and registers a
     * typed stub view when the id is not present in the graph.
     *
     * <p>The stub type is inferred from the id format:
     * <ul>
     *   <li>ids of the form {@code pkg.ClassName#ClassName(params)} or
     *       {@code pkg.ClassName#<init>(params)} → {@link ConstructorNodeView} stub</li>
     *   <li>other ids containing {@code #} and {@code (} →
     *       {@link MethodNodeView} stub</li>
     *   <li>ids containing {@code #} but <em>no</em> {@code (} →
     *       {@link ConstructorNodeView} stub</li>
     *   <li>otherwise → {@link ClassNodeView} stub</li>
     * </ul>
     *
     * <p>Stub nodes receive {@code startLine = -1} and {@code filePath = "external"}
     * so that {@link com.example.mrrag.view.GraphNodeView#toMarkdown()} emits
     * {@code -1|<id>} lines for them.
     */
    private GraphNodeView resolve(ViewGraph vg, String id) {
        GraphNodeView existing = vg.byId(id);
        if (existing != null) return existing;

        boolean hasHash  = id != null && id.contains("#");
        boolean hasParen = id != null && id.contains("(");

        final NodeKind stubKind;
        if (hasHash && hasParen) {
            // Distinguish constructors from methods: constructor id has the form
            // "pkg.ClassName#ClassName(...)" or "pkg.ClassName#<init>(...)"
            stubKind = isConstructorId(id) ? NodeKind.CONSTRUCTOR : NodeKind.METHOD;
        } else if (hasHash) {
            stubKind = NodeKind.CONSTRUCTOR;
        } else {
            stubKind = NodeKind.CLASS;
        }

        GraphNode stub = new GraphNode(
                id,
                stubKind,
                simpleNameOf(id),
                "external",
                -1, -1,   // -1 = external/synthetic: no source position
                "",       // sourceSnippet
                ""        // declarationSnippet
        );

        GraphNodeView stubView = switch (stubKind) {
            case METHOD      -> new MethodNodeView(stub);
            case CONSTRUCTOR -> new ConstructorNodeView(stub);
            default          -> new ClassNodeView(stub);
        };

        vg.put(stubView);
        log.trace("GraphViewBuilder: created {} stub for external id '{}'",
                stubKind, id);
        return stubView;
    }

    /**
     * Returns {@code true} when {@code id} looks like a constructor reference:
     * <ul>
     *   <li>{@code pkg.ClassName#ClassName(params)} — Spoon canonical form</li>
     *   <li>{@code pkg.ClassName#<init>(params)}    — normalised form</li>
     * </ul>
     */
    private static boolean isConstructorId(String id) {
        int hash = id.indexOf('#');
        if (hash < 0) return false;
        String afterHash = id.substring(hash + 1);
        // normalised form
        if (afterHash.startsWith("<init>(")) return true;
        // Spoon form: ClassName#ClassName(...) — simple class name before '('
        String owner = id.substring(0, hash);
        int dot = owner.lastIndexOf('.');
        String simpleName = dot >= 0 ? owner.substring(dot + 1) : owner;
        // inner class: OuterClass$Inner → simpleName is "Inner"
        int dollar = simpleName.lastIndexOf('$');
        if (dollar >= 0) simpleName = simpleName.substring(dollar + 1);
        if (afterHash.startsWith(simpleName + "(")) return true;
        int open = afterHash.indexOf('(');
        if (open > 0 && afterHash.lastIndexOf(')') == afterHash.length() - 1) {
            String nameBeforeParams = afterHash.substring(0, open);
            return nameBeforeParams.equals(simpleName)
                    || nameBeforeParams.endsWith("." + simpleName);
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
