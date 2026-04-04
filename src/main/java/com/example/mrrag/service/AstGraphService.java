package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.service.source.ProjectSourceProvider;
import com.example.mrrag.service.source.VirtualSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Default implementation of {@link GraphBuildService}.
 *
 * <p>Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p><b>Nodes:</b> CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
 * LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES,
 * INSTANTIATES_ANONYMOUS, REFERENCES_METHOD, READS_FIELD, WRITES_FIELD,
 * READS_LOCAL_VAR, WRITES_LOCAL_VAR, THROWS, ANNOTATED_WITH,
 * REFERENCES_TYPE, OVERRIDES, HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
 *
 * <p>Source files are supplied by a {@link ProjectSourceProvider}:
 * <ul>
 *   <li>{@link com.example.mrrag.service.source.GitLabProjectSourceProvider} —
 *       fetches files from the GitLab API (no {@code git clone}).</li>
 *   <li>{@link com.example.mrrag.service.source.LocalCloneProjectSourceProvider} —
 *       reads files from a locally cloned directory.</li>
 * </ul>
 *
 * <p>Both providers convert files to {@link VirtualSource} records; from this
 * point on the pipeline is identical regardless of the source origin.
 */
@Slf4j
@Service
public class AstGraphService implements GraphBuildService {

    // ------------------------------------------------------------------
    // Public model
    // ------------------------------------------------------------------

    /** Kinds of graph nodes. */
    public enum NodeKind {
        CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
        LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE
    }

    /** Kinds of directed graph edges. */
    public enum EdgeKind {
        DECLARES, EXTENDS, IMPLEMENTS,
        INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS, REFERENCES_METHOD,
        READS_FIELD, WRITES_FIELD,
        READS_LOCAL_VAR, WRITES_LOCAL_VAR,
        THROWS, ANNOTATED_WITH, REFERENCES_TYPE, OVERRIDES,
        HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
    }

    public record GraphNode(
            String id, NodeKind kind, String simpleName,
            String filePath, int startLine, int endLine, String sourceSnippet) {}

    public record GraphEdge(
            String caller, EdgeKind kind, String callee,
            String filePath, int line) {}

    public static class ProjectGraph {
        public final Map<String, GraphNode>       nodes        = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesFrom    = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesTo      = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> bySimpleName = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byLine       = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byFile       = new LinkedHashMap<>();

        public Set<String> allFilePaths() { return byFile.keySet(); }

        void addNode(GraphNode n) {
            nodes.put(n.id(), n);
            bySimpleName.computeIfAbsent(n.simpleName(), k -> new ArrayList<>()).add(n);
            byLine.computeIfAbsent(n.filePath() + "#" + n.startLine(), k -> new ArrayList<>()).add(n);
            byFile.computeIfAbsent(n.filePath(), k -> new ArrayList<>()).add(n);
        }

        void addEdge(GraphEdge e) {
            edgesFrom.computeIfAbsent(e.caller(), k -> new ArrayList<>()).add(e);
            edgesTo.computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(e);
        }

        public List<GraphEdge> outgoing(String nodeId) {
            return edgesFrom.getOrDefault(nodeId, List.of());
        }
        public List<GraphEdge> incoming(String nodeId) {
            return edgesTo.getOrDefault(nodeId, List.of());
        }
        public List<GraphEdge> outgoing(String nodeId, EdgeKind kind) {
            return outgoing(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }
        public List<GraphEdge> incoming(String nodeId, EdgeKind kind) {
            return incoming(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }
        public List<GraphNode> nodesAtLine(String relPath, int line) {
            return byFile.getOrDefault(relPath, List.of()).stream()
                    .filter(n -> n.startLine() <= line && n.endLine() >= line)
                    .toList();
        }
    }

    // ------------------------------------------------------------------
    // Dependencies & cache
    // ------------------------------------------------------------------

    private final EdgeKindConfig edgeConfig;
    /** Cache keyed by {@link ProjectSourceProvider#projectKey()}. */
    private final Map<Object, ProjectGraph> cache = new ConcurrentHashMap<>();

    public AstGraphService(EdgeKindConfig edgeConfig) {
        this.edgeConfig = edgeConfig;
    }

    // ------------------------------------------------------------------
    // GraphBuildService — primary API
    // ------------------------------------------------------------------

    /**
     * Builds (or returns a cached) symbol graph using the supplied provider.
     * Caching is keyed on {@link ProjectSourceProvider#projectKey()}.
     */
    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider sourceProvider) throws Exception {
        Object key = sourceProvider.projectKey();
        ProjectGraph cached = cache.get(key);
        if (cached != null) {
            log.debug("Returning cached graph for key={}", key);
            return cached;
        }
        ProjectGraph graph = doBuildFromProvider(sourceProvider);
        cache.put(key, graph);
        return graph;
    }

    @Override
    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String normalised = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (normalised.equals(knownNorm)) return known;
            if (normalised.endsWith("/" + knownNorm)) return known;
            if (knownNorm.endsWith("/" + normalised)) return known;
        }
        String[] parts = normalised.split("/");
        for (int i = 1; i < parts.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            if (graph.byFile.containsKey(candidate)) return candidate;
        }
        log.debug("normalizeFilePath: no match in graph for '{}'", diffPath);
        return diffPath;
    }

    @Override
    public void invalidate(Object projectKey) {
        cache.remove(projectKey);
        log.debug("Invalidated graph cache for key={}", projectKey);
    }

    // ------------------------------------------------------------------
    // Backward-compatible helpers (used by legacy callers)
    // ------------------------------------------------------------------

    /**
     * Builds a graph from a locally cloned directory.
     *
     * @deprecated Use {@link #buildGraph(ProjectSourceProvider)} with
     *             {@link com.example.mrrag.service.source.LocalCloneProjectSourceProvider}.
     */
    @Deprecated
    public ProjectGraph buildGraph(Path projectRoot) {
        try {
            return buildGraph(
                    new com.example.mrrag.service.source.LocalCloneProjectSourceProvider(projectRoot));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph for " + projectRoot, e);
        }
    }

    /**
     * Builds a graph from pre-fetched virtual sources.
     *
     * @deprecated Use {@link #buildGraph(ProjectSourceProvider)} with a custom
     *             {@link ProjectSourceProvider} that returns the given list.
     */
    @Deprecated
    public ProjectGraph buildGraphFromVirtualSources(
            List<com.example.mrrag.service.loader.VirtualSource> sources) {
        try {
            return buildGraph(new ProjectSourceProvider() {
                @Override public Object projectKey() { return null; } // no caching
                @Override public List<VirtualSource> loadSources() {
                    return sources.stream()
                            .map(s -> new VirtualSource(s.path(), s.content()))
                            .toList();
                }
                @Override public String rootHint() { return ""; }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph from virtual sources", e);
        }
    }

    // ------------------------------------------------------------------
    // Core build logic
    // ------------------------------------------------------------------

    private ProjectGraph doBuildFromProvider(ProjectSourceProvider provider) throws Exception {
        List<VirtualSource> sources = provider.loadSources();
        log.info("Building Spoon AST graph from {} sources via {}",
                sources.size(), provider.getClass().getSimpleName());

        Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
        for (VirtualSource src : sources) {
            sourceLines.put(src.path(), src.content().split("\n", -1));
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) {}

        for (VirtualSource src : sources) {
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        CtModel model;
        try {
            model = launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException via {} — partial model: {}",
                    provider.getClass().getSimpleName(), mbe.getMessage());
            model = launcher.getModel();
            if (model == null) {
                log.error("Spoon returned null model — returning empty graph");
                return new ProjectGraph();
            }
        }

        // rootHint: empty string in virtual/GitLab mode, absolute path in local-clone mode
        return doBuildGraphFromModel(model, sourceLines, provider.rootHint());
    }

    // ------------------------------------------------------------------
    // Graph construction – shared core (model → graph)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model,
                                               Map<String, String[]> sourceLines,
                                               String root) {
        var graph = new ProjectGraph();

        // ── 1. Type nodes ────────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = qualifiedName(type);
            if (id == null) return;
            String file = relPath(root, sourceFile(type));
            int[] ln = lines(type);
            NodeKind kind;
            if (type instanceof CtAnnotationType) kind = NodeKind.ANNOTATION;
            else if (type instanceof CtInterface) kind = NodeKind.INTERFACE;
            else kind = NodeKind.CLASS;
            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    extractSource(sourceLines, file, ln[0], ln[1], type)));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                type.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
            }
        });

        // ── 2. EXTENDS / IMPLEMENTS ─────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type);
                if (id == null) return;
                String file = relPath(root, sourceFile(type));
                int[] ln = lines(type);
                if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                    if (type instanceof CtClass<?> cls && cls.getSuperclass() != null)
                        graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                cls.getSuperclass().getQualifiedName(), file, ln[0]));
                    if (type instanceof CtInterface<?> iface)
                        iface.getSuperInterfaces().forEach(s -> graph.addEdge(new GraphEdge(
                                id, EdgeKind.EXTENDS, s.getQualifiedName(), file, ln[0])));
                }
                if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                    if (type instanceof CtClass<?> cls)
                        cls.getSuperInterfaces().forEach(i -> graph.addEdge(new GraphEdge(
                                id, EdgeKind.IMPLEMENTS, i.getQualifiedName(), file, ln[0])));
                }
            });
        }

        // ── 3. TYPE_PARAM nodes ──────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = formalDeclarerId(declarer);
                if (ownerId == null) return;
                String file = relPath(root, sourceFile((CtElement) declarer));
                int[] ownerLn = lines((CtElement) declarer);
                declarer.getFormalCtTypeParameters().forEach(tp -> {
                    String tpId = typeParamId(ownerId, tp);
                    int[] tpLn = lines(tp);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            extractSource(sourceLines, file, tpLn[0], tpLn[1], tp)));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_TYPE_PARAM, tpId, file, ownerLn[0]));
                    if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                        CtTypeReference<?> sc = tp.getSuperclass();
                        if (sc != null && !sc.getQualifiedName().equals("java.lang.Object"))
                            graph.addEdge(new GraphEdge(tpId, EdgeKind.HAS_BOUND,
                                    sc.getQualifiedName(), file, tpLn[0]));
                        tp.getSuperInterfaces().forEach(b -> graph.addEdge(new GraphEdge(
                                tpId, EdgeKind.HAS_BOUND, b.getQualifiedName(), file, tpLn[0])));
                    }
                });
            });
        }

        // ── 4. Method nodes ───────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = typeMemberExecId(m);
            if (id == null) return;
            String file = relPath(root, sourceFile(m));
            int[] ln = lines(m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], m)));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String classId = qualifiedName(m.getDeclaringType());
                if (classId != null)
                    graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
            }
            m.getTopDefinitions().stream().findFirst()
                    .filter(top -> top != m)
                    .ifPresent(superId -> graph.addEdge(new GraphEdge(
                            id, EdgeKind.OVERRIDES, typeMemberExecId((CtTypeMember) superId), file, ln[0])));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ── 5. Constructor nodes ────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = typeMemberExecId(c);
            if (id == null) return;
            String file = relPath(root, sourceFile(c));
            int[] ln = lines(c);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], c)));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String classId = qualifiedName(c.getDeclaringType());
                if (classId != null)
                    graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
            }
        });

        // ── 6. Field nodes ────────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = fieldId(field);
            if (id == null) return;
            String file = relPath(root, sourceFile(field));
            int[] ln = lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], field)));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String classId = qualifiedName(field.getDeclaringType());
                if (classId != null)
                    graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ── 7. Local variable + parameter nodes ───────────────────────────────────
        model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = varId(v);
            if (id == null) return;
            String file = relPath(root, sourceFile(v));
            int[] ln = lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], v)));
        });

        // ── 8. ANNOTATION_ATTRIBUTE nodes ──────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = qualifiedName(ann);
                if (annoId == null) return;
                String file = relPath(root, sourceFile(ann));
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods =
                        (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName();
                    int[] ln = lines(m);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            extractSource(sourceLines, file, ln[0], ln[1], m)));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]));
                });
            });
        }

        // ── 9. Lambda nodes ────────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = relPath(root, sourceFile(lambda));
            int[] ln = lines(lambda);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], lambda)));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                CtMethod<?> em = lambda.getParent(CtMethod.class);
                if (em != null) {
                    String encId = typeMemberExecId(em);
                    if (encId != null)
                        graph.addEdge(new GraphEdge(encId, EdgeKind.DECLARES, id, file, ln[0]));
                } else {
                    CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                    if (ec != null) {
                        String encId = typeMemberExecId(ec);
                        if (encId != null)
                            graph.addEdge(new GraphEdge(encId, EdgeKind.DECLARES, id, file, ln[0]));
                    }
                }
            }
        });

        // ── 10. INVOKES ───────────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
            model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = nearestExecId(inv);
                if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        execRefId(inv.getExecutable()),
                        relPath(root, sourceFile(inv)), posLine(inv)));
            });
        }

        // ── 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ───────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES)
                || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS)) {
            model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = nearestExecId(cc);
                if (callerId == null) return;
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                boolean isAnon = cc instanceof CtNewClass;
                EdgeKind kind = isAnon ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(kind))
                    graph.addEdge(new GraphEdge(callerId, kind, typeId,
                            relPath(root, sourceFile(cc)), posLine(cc)));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            execRefId(cc.getExecutable()),
                            relPath(root, sourceFile(cc)), posLine(cc)));
            });
        }

        // ── 12. REFERENCES_METHOD ─────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD)) {
            model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = nearestExecId(ref);
                if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        execRefId(ref.getExecutable()),
                        relPath(root, sourceFile(ref)), posLine(ref)));
            });
        }

        // ── 13. READS_FIELD / WRITES_FIELD ────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD)
                || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD)) {
            model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                String execId = nearestExecId(fa);
                if (execId == null) return;
                CtFieldReference<?> ref = fa.getVariable();
                String fId = ref.getDeclaringType() != null
                        ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                        : "?." + ref.getSimpleName();
                EdgeKind kind = (fa instanceof CtFieldWrite)
                        ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(kind))
                    graph.addEdge(new GraphEdge(execId, kind, fId,
                            relPath(root, sourceFile(fa)), posLine(fa)));
            });
        }

        // ── 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ─────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR)
                || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR)) {
            model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String execId = nearestExecId(va);
                if (execId == null) return;
                String vId = varRefId(va.getVariable());
                EdgeKind kind = (va instanceof CtVariableWrite)
                        ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(kind))
                    graph.addEdge(new GraphEdge(execId, kind, vId,
                            relPath(root, sourceFile(va)), posLine(va)));
            });
        }

        // ── 15. THROWS ────────────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.THROWS)) {
            model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String execId = nearestExecId(thr);
                if (execId == null) return;
                CtExpression<?> thrown = thr.getThrownExpression();
                String typeId = "?";
                if (thrown instanceof CtConstructorCall<?> cc
                        && cc.getExecutable().getDeclaringType() != null)
                    typeId = cc.getExecutable().getDeclaringType().getQualifiedName();
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        relPath(root, sourceFile(thr)), posLine(thr)));
            });
        }

        // ── 16. REFERENCES_TYPE ───────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE)) {
            model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = nearestExecId(ta);
                if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        relPath(root, sourceFile(ta)), posLine(ta)));
            });
        }

        log.info("AST graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }

    // ------------------------------------------------------------------
    // Source snippet helpers
    // ------------------------------------------------------------------

    private static String extractSource(Map<String, String[]> sourceLines,
                                        String filePath, int startLine, int endLine,
                                        CtElement el) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = sourceLines.get(filePath);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to   = Math.min(lines.length, endLine);
                if (from < to)
                    return String.join("\n", Arrays.copyOfRange(lines, from, to));
            }
        }
        return snippet(el);
    }

    private static String snippet(CtElement el) {
        try { String s = el.toString(); return s != null ? s : ""; }
        catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------
    // ID helpers
    // ------------------------------------------------------------------

    private String qualifiedName(CtType<?> type) {
        if (type == null) return null;
        String q = type.getQualifiedName();
        return (q == null || q.isBlank()) ? null : q;
    }

    private String typeMemberExecId(CtTypeMember member) {
        if (member == null) return null;
        try {
            CtType<?> declaring = member.getDeclaringType();
            String owner = declaring != null ? declaring.getQualifiedName() : "?";
            if (member instanceof CtExecutable<?> exec)
                return owner + "#" + exec.getSignature();
            return owner + "#" + member.getSimpleName();
        } catch (Exception e) { return null; }
    }

    private String execRefId(CtExecutableReference<?> ref) {
        if (ref == null) return "unresolved";
        try {
            String owner = ref.getDeclaringType() != null
                    ? ref.getDeclaringType().getQualifiedName() : "?";
            return owner + "#" + ref.getSignature();
        } catch (Exception e) { return "unresolved:" + ref.getSimpleName(); }
    }

    private String fieldId(CtField<?> field) {
        if (field.getDeclaringType() == null) return null;
        return field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName();
    }

    private String varId(CtVariable<?> v) {
        if (!v.getPosition().isValidPosition()) return null;
        String file = v.getPosition().getFile() != null
                ? v.getPosition().getFile().getName() : "?";
        return "var@" + file + ":" + v.getPosition().getLine() + ":" + v.getSimpleName();
    }

    private String varRefId(CtVariableReference<?> ref) {
        if (ref == null) return "var@?";
        try {
            CtVariable<?> decl = ref.getDeclaration();
            if (decl != null) return varId(decl);
        } catch (Exception ignored) {}
        return "var@" + ref.getSimpleName();
    }

    private String typeParamId(String ownerId, CtTypeParameter tp) {
        return ownerId + "#<" + tp.getSimpleName() + ">";
    }

    private String formalDeclarerId(CtFormalTypeDeclarer declarer) {
        if (declarer instanceof CtType<?> t) return qualifiedName(t);
        if (declarer instanceof CtTypeMember m) return typeMemberExecId(m);
        return null;
    }

    private String nearestExecId(CtElement el) {
        CtMethod<?> m = el.getParent(CtMethod.class);
        if (m != null) return typeMemberExecId(m);
        CtConstructor<?> c = el.getParent(CtConstructor.class);
        if (c != null) return typeMemberExecId(c);
        return null;
    }

    // ------------------------------------------------------------------
    // Position / path helpers
    // ------------------------------------------------------------------

    private String sourceFile(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition()) {
                if (pos.getFile() != null) return pos.getFile().getAbsolutePath();
                var cu = el.getPosition().getCompilationUnit();
                if (cu != null) {
                    String unitFile = cu.getFile() != null
                            ? cu.getFile().getPath()
                            : (cu.getMainType() != null
                                    ? cu.getMainType().getQualifiedName().replace('.', '/') + ".java"
                                    : "");
                    if (!unitFile.isEmpty()) return unitFile;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String relPath(String root, String abs) {
        if (abs.isEmpty()) return "unknown";
        if (root.isEmpty()) return abs;
        return abs.startsWith(root)
                ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    private int[] lines(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition()) return new int[]{ pos.getLine(), pos.getEndLine() };
        } catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    private int posLine(CtElement el) {
        try {
            var pos = el.getPosition();
            return pos.isValidPosition() ? pos.getLine() : -1;
        } catch (Exception e) { return -1; }
    }
}
