package com.example.mrrag.service;

import com.example.mrrag.service.AstGraphService.*;
import com.example.mrrag.view.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Builds a fully cross-linked view graph from a {@link ProjectGraph}.
 *
 * <h3>Two-pass algorithm</h3>
 * <ol>
 *   <li><b>Pass 1 – instantiate views.</b>  For every {@link GraphNode} in the
 *       graph one typed view ({@link ClassNodeView}, {@link MethodNodeView}, …)
 *       is created and stored in an id→view map.</li>
 *   <li><b>Pass 2 – wire edges.</b>  Every {@link GraphEdge} is translated into
 *       a direct Java reference on both the source and the target view.</li>
 * </ol>
 *
 * <p>After {@link #build(ProjectGraph)} returns, any view can be used as the
 * entry-point for a full graph traversal:
 * <pre>{@code
 *   GraphViewBuilder builder = ...;           // injected
 *   ViewGraph vg = builder.build(projectGraph);
 *
 *   ClassNodeView foo = vg.classById("com.example.Foo");
 *   foo.getMethods().forEach(m -> {
 *       m.getCallers().forEach(caller -> System.out.println(caller.getId()));
 *   });
 * }</pre>
 *
 * <h3>Unknown / external nodes</h3>
 * Target IDs that have no corresponding {@link GraphNode} in the graph
 * (e.g. JDK types such as {@code java.util.List}) are represented by a
 * lightweight "stub" {@link ClassNodeView} so that edge lists are never
 * {@code null} and traversal never throws {@link NullPointerException}.
 */
@Slf4j
@Service
public class GraphViewBuilder {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Container returned by {@link #build(ProjectGraph)}.
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

        /** All {@link ClassNodeView} instances in the graph. */
        public List<ClassNodeView> allClasses() {
            return byId.values().stream()
                    .filter(v -> v instanceof ClassNodeView)
                    .map(v -> (ClassNodeView) v)
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
                case CLASS, ANNOTATION -> new ClassNodeView(node);
                case METHOD            -> new MethodNodeView(node);
                case CONSTRUCTOR       -> new ConstructorNodeView(node);
                case FIELD             -> new FieldNodeView(node);
                case VARIABLE          -> new VariableNodeView(node);
                case LAMBDA            -> new LambdaNodeView(node);
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
                // to = owner, from = member
                from.addDeclaredBy(to);
                if (to instanceof ClassNodeView cls) {
                    if      (from instanceof MethodNodeView m)      { cls.addMethod(m);      m.setDeclaredByClass(cls); }
                    else if (from instanceof ConstructorNodeView c) { cls.addConstructor(c); c.setDeclaredByClass(cls); }
                    else if (from instanceof FieldNodeView f)       { cls.addField(f);       f.setDeclaredByClass(cls); }
                    else if (from instanceof ClassNodeView ic)      { cls.addInnerClass(ic); }
                    else if (from instanceof LambdaNodeView l)      { cls.addLambda(l); }
                }
                if (to instanceof MethodNodeView m && from instanceof LambdaNodeView l) {
                    m.addLambda(l); l.setDeclaredByExecutable(m);
                }
                if (to instanceof ConstructorNodeView c && from instanceof LambdaNodeView l) {
                    c.addLambda(l); l.setDeclaredByExecutable(c);
                }
            }

            // ── Generic type parameters ───────────────────────────────────────
            case HAS_TYPE_PARAM -> {
                // from = owning class, to = type parameter
                if (from instanceof ClassNodeView cls && to instanceof TypeParamNodeView tp) {
                    cls.addTypeParameter(tp);
                }
            }

            // ── Annotation attributes ─────────────────────────────────────────
            case ANNOTATION_ATTR -> {
                // from = @interface class, to = attribute element
                if (from instanceof ClassNodeView cls && to instanceof AnnotationAttributeView attr) {
                    cls.addAnnotationAttribute(attr);
                }
            }

            // ── Type hierarchy ────────────────────────────────────────────────
            case EXTENDS -> {
                if (from instanceof ClassNodeView sub && to instanceof ClassNodeView sup) {
                    sub.setSuperClass(sup);
                    sup.addSubClass(sub);
                }
            }
            case IMPLEMENTS -> {
                if (from instanceof ClassNodeView impl && to instanceof ClassNodeView iface) {
                    impl.addInterface(iface);
                    iface.addImplementation(impl);
                }
            }

            // ── Invocations ───────────────────────────────────────────────────
            case INVOKES -> addCallerCallee(from, to);

            case INSTANTIATES -> {
                if (to instanceof ClassNodeView cls) {
                    cls.addInstantiatedBy(from);
                    if      (from instanceof MethodNodeView m)      m.addInstantiates(cls);
                    else if (from instanceof ConstructorNodeView c) c.addInstantiates(cls);
                    else if (from instanceof LambdaNodeView l)      l.addInstantiates(cls);
                }
            }
            case INSTANTIATES_ANONYMOUS -> {
                if (to instanceof ClassNodeView cls) {
                    cls.addAnonymouslyInstantiatedBy(from);
                    if      (from instanceof MethodNodeView m)      m.addInstantiatesAnon(cls);
                    else if (from instanceof ConstructorNodeView c) c.addInstantiatesAnon(cls);
                    else if (from instanceof LambdaNodeView l)      l.addInstantiatesAnon(cls);
                }
            }
            case REFERENCES_METHOD -> {
                if      (from instanceof MethodNodeView m)      m.addReferencedMethod(to);
                else if (from instanceof ConstructorNodeView c) c.addCallee(to);
                else if (from instanceof LambdaNodeView l)      l.addCallee(to);
            }

            // ── Field access ──────────────────────────────────────────────────
            case READS_FIELD -> {
                if (to instanceof FieldNodeView f) {
                    f.addReadBy(from);
                    if      (from instanceof MethodNodeView m)      m.addReadsField(f);
                    else if (from instanceof ConstructorNodeView c) c.addReadsField(f);
                    else if (from instanceof LambdaNodeView l)      l.addReadsField(f);
                }
            }
            case WRITES_FIELD -> {
                if (to instanceof FieldNodeView f) {
                    f.addWrittenBy(from);
                    if      (from instanceof MethodNodeView m)      m.addWritesField(f);
                    else if (from instanceof ConstructorNodeView c) c.addWritesField(f);
                    else if (from instanceof LambdaNodeView l)      l.addWritesField(f);
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
                if      (from instanceof MethodNodeView m)      m.addThrowsType(to);
                else if (from instanceof ConstructorNodeView c) c.addThrowsType(to);
                else if (from instanceof LambdaNodeView l)      l.addThrowsType(to);
            }

            // ── Annotations ───────────────────────────────────────────────────
            case ANNOTATED_WITH -> {
                if (to instanceof ClassNodeView annType) annType.addAnnotatedNode(from);
                if      (from instanceof ClassNodeView cls)        cls.addAnnotation(to);
                else if (from instanceof MethodNodeView m)         m.addAnnotation(to);
                else if (from instanceof FieldNodeView f)          f.addAnnotation(to);
                else if (from instanceof ConstructorNodeView c)    c.addAnnotation(to);
            }

            // ── Type references ───────────────────────────────────────────────
            case REFERENCES_TYPE -> {
                if (to instanceof ClassNodeView cls) cls.addReferencedBy(from);
                if      (from instanceof MethodNodeView m)      m.addReferencesType(to);
                else if (from instanceof ConstructorNodeView c) c.addReferencesType(to);
                else if (from instanceof LambdaNodeView l)      l.addReferencesType(to);
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

    private void addCallerCallee(GraphNodeView from, GraphNodeView to) {
        if      (from instanceof MethodNodeView m)      m.addCallee(to);
        else if (from instanceof ConstructorNodeView c) c.addCallee(to);
        else if (from instanceof LambdaNodeView l)      l.addCallee(to);

        if      (to instanceof MethodNodeView m)        m.addCaller(from);
        else if (to instanceof ConstructorNodeView c)   c.addCaller(from);
        else if (to instanceof LambdaNodeView l)        l.addCaller(from);
    }

    // ------------------------------------------------------------------
    // Helper: stub creation for external / unresolved nodes
    // ------------------------------------------------------------------

    /**
     * Resolves an id to a view.  If the id is not present in the view graph
     * (e.g. a JDK type), a lightweight stub {@link ClassNodeView} is created
     * on-the-fly so that edge lists are never null.
     */
    private GraphNodeView resolve(ViewGraph vg, String id) {
        GraphNodeView existing = vg.byId(id);
        if (existing != null) return existing;

        GraphNode stub = new GraphNode(
                id,
                NodeKind.CLASS,
                simpleNameOf(id),
                "external",
                0, 0,
                null
        );
        ClassNodeView stubView = new ClassNodeView(stub);
        vg.put(stubView);
        log.trace("GraphViewBuilder: created stub view for external id '{}'", id);
        return stubView;
    }

    /** Extracts the simple name from a qualified id (last segment after {@code .} or {@code #}). */
    private static String simpleNameOf(String id) {
        if (id == null || id.isBlank()) return "?";
        int hash = id.lastIndexOf('#');
        if (hash >= 0) return id.substring(hash + 1);
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }
}
