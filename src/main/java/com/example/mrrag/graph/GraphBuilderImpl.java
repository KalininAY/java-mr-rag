package com.example.mrrag.graph;

import com.example.mrrag.app.config.EdgeKindConfig;
import com.example.mrrag.app.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.graph.model.*;
import com.example.mrrag.graph.raw.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p>All ID computation, snippet extraction, and path-normalisation helpers
 * are delegated to {@link AstGraphUtils} — this class focuses purely on
 * orchestration and Spoon model traversal.
 *
 * <p>Implements {@link GraphBuilder}: accepts any {@link ProjectSourceProvider}
 * so graph construction is fully decoupled from how source files are obtained.
 *
 * <p>{@link GraphNode#bodyHash()} is computed automatically inside the
 * {@code GraphNode} compact constructor — callers pass {@code null} and the
 * record handles it.
 */
@Slf4j
@Component
public class GraphBuilderImpl implements GraphBuilder {

    // ------------------------------------------------------------------
    // Dependencies & caches
    // ------------------------------------------------------------------

    private final EdgeKindConfig         edgeConfig;
    private final ProjectGraphCacheStore cacheStore;

    /** In-memory cache keyed by {@link ProjectKey} (path + fingerprint). */
    private final Map<ProjectKey, ProjectGraph> keyCache = new ConcurrentHashMap<>();

    @Autowired
    public GraphBuilderImpl(EdgeKindConfig edgeConfig,
                            ProjectGraphCacheStore cacheStore) {
        this.edgeConfig = edgeConfig;
        this.cacheStore = cacheStore;
    }

    /** Minimal constructor for callers that do not need disk-cache support. */
    public GraphBuilderImpl(EdgeKindConfig edgeConfig) {
        this(edgeConfig, null);
    }

    // ------------------------------------------------------------------
    // ProjectKey helpers
    // ------------------------------------------------------------------

    public ProjectKey projectKey(Path projectRoot) {
        return new ProjectKey(projectRoot, ProjectFingerprint.compute(projectRoot));
    }

    // ------------------------------------------------------------------
    // ProjectKey-based entry point
    // ------------------------------------------------------------------

    public ProjectGraph buildGraph(ProjectKey key) throws Exception {
        ProjectGraph cached = keyCache.get(key);
        if (cached != null) {
            log.debug("buildGraph(ProjectKey): in-memory cache hit for {}", key);
            return cached;
        }
        if (cacheStore != null) {
            var loaded = cacheStore.tryLoadAllSegments(key);
            if (loaded.isPresent()) {
                ProjectGraph main = loaded.get().getOrDefault(GraphSegmentIds.MAIN, new ProjectGraph());
                keyCache.put(key, main);
                log.debug("buildGraph(ProjectKey): disk cache hit for {}", key);
                return main;
            }
        }
        log.info("buildGraph(ProjectKey): building from source for {}", key.projectRoot());
        ProjectGraph graph = buildGraph(new LocalCloneProjectSourceProvider(key.projectRoot()));
        keyCache.put(key, graph);
        if (cacheStore != null) {
            Map<String, ProjectGraph> segments = new LinkedHashMap<>();
            segments.put(GraphSegmentIds.MAIN, graph);
            try {
                cacheStore.savePartitioned(key, segments);
                log.debug("buildGraph(ProjectKey): saved to disk cache for {}", key);
            } catch (Exception e) {
                log.warn("buildGraph(ProjectKey): failed to save cache for {}: {}", key, e.getMessage());
            }
        }
        return graph;
    }

    public void invalidate(ProjectKey key) {
        keyCache.remove(key);
        if (cacheStore != null) {
            cacheStore.delete(key);
            log.debug("invalidate(ProjectKey): deleted disk cache for {}", key);
        }
        log.debug("invalidate(ProjectKey): evicted in-memory entry for {}", key);
    }

    // ------------------------------------------------------------------
    // Primary entry point (GraphBuilder interface)
    // ------------------------------------------------------------------

    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        log.info("Building AST graph via provider: {}", provider.getClass().getSimpleName());
        List<ProjectSource> sources = provider.getSources();
        log.info("Provider supplied {} .java files", sources.size());
        return doBuildGraphFromSources(sources, provider.localProjectRoot().orElse(null));
    }

    /**
     * Invalidates all caches (in-memory and disk) for the given project root.
     *
     * <p>The {@link ProjectKey} is recomputed from the path — note that if the
     * project fingerprint changed since the last build, the old disk-cache entry
     * will not be found and only the in-memory entry (if any) will be evicted.
     * Use {@link #invalidate(ProjectKey)} directly when you already have the key.
     */
    @Override
    public void invalidate(Path projectRoot) {
        ProjectKey key = projectKey(projectRoot);
        keyCache.remove(key);
        log.debug("invalidate(Path): evicted in-memory entry for {}", projectRoot);
        if (cacheStore != null) {
            cacheStore.delete(key);
            log.debug("invalidate(Path): deleted disk cache for {}", projectRoot);
        }
    }

    // ------------------------------------------------------------------
    // Path normalisation (delegated to AstGraphUtils)
    // ------------------------------------------------------------------

    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        return AstGraphUtils.normalizeFilePath(diffPath, graph);
    }

    // ------------------------------------------------------------------
    // Backward-compat
    // ------------------------------------------------------------------

    @Deprecated
    public ProjectGraph buildGraphFromProjectSources(List<ProjectSource> sources) throws Exception {
        return doBuildGraphFromSources(
                sources.stream().map(s -> new ProjectSource(s.path(), s.content())).toList(),
                null);
    }

    // ------------------------------------------------------------------
    // Core: sources → Spoon model
    // ------------------------------------------------------------------

    private ProjectGraph doBuildGraphFromSources(List<ProjectSource> sources, Path classpathRoot) {
        Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
        for (ProjectSource src : sources) {
            sourceLines.put(src.path(), src.content().split("\n", -1));
        }

        Launcher launcher = new Launcher();
        ClasspathResolver.tryResolve(classpathRoot).ifPresentOrElse(
                r -> {
                    launcher.getEnvironment().setNoClasspath(false);
                    launcher.getEnvironment().setSourceClasspath(r.entries());
                    log.info("Spoon using {} compileClasspath ({} entries) for {}",
                            r.source(), r.entries().length, classpathRoot);
                },
                () -> {
                    launcher.getEnvironment().setNoClasspath(true);
                    log.debug("Spoon noClasspath mode (classpath unavailable) for {}", classpathRoot);
                });
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try { launcher.getEnvironment().setIgnoreDuplicateDeclarations(true); }
        catch (NoSuchMethodError ignored) {}

        for (ProjectSource src : sources) {
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        CtModel model;
        try {
            model = launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException — partial model. Cause: {}", mbe.getMessage());
            model = launcher.getModel();
            if (model == null) {
                log.error("Spoon returned null model — returning empty graph");
                return new ProjectGraph();
            }
        }
        return doBuildGraphFromModel(model, sourceLines, "");
    }

    // ------------------------------------------------------------------
    // Core: Spoon model → ProjectGraph
    //
    // ID helpers, snippet extraction and path utilities are delegated to
    // AstGraphUtils to keep this method focused on traversal logic.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model,
                                               Map<String, String[]> sourceLines,
                                               String root) {
        var graph = new ProjectGraph();

        // ─ 1. Type nodes ───────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = AstGraphUtils.qualifiedName(type); if (id == null) return;
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(type));
            int[] ln = AstGraphUtils.lines(type);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                          : (type instanceof CtInterface)      ? NodeKind.INTERFACE
                          :                                      NodeKind.CLASS;
            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], type), "", null));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ─ 2. EXTENDS / IMPLEMENTS ────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = AstGraphUtils.qualifiedName(type); if (id == null) return;
                String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(type));
                int[] ln = AstGraphUtils.lines(type);
                if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                    if (type instanceof CtClass<?> cls && cls.getSuperclass() != null)
                        graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                cls.getSuperclass().getQualifiedName(), file, ln[0]));
                    if (type instanceof CtInterface<?> iface)
                        iface.getSuperInterfaces().forEach(s -> graph.addEdge(
                                new GraphEdge(id, EdgeKind.EXTENDS, s.getQualifiedName(), file, ln[0])));
                }
                if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS) && type instanceof CtClass<?> cls)
                    cls.getSuperInterfaces().forEach(i -> graph.addEdge(
                            new GraphEdge(id, EdgeKind.IMPLEMENTS, i.getQualifiedName(), file, ln[0])));
            });
        }

        // ─ 3. TYPE_PARAM nodes ─────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = AstGraphUtils.formalDeclarerId(declarer); if (ownerId == null) return;
                String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile((CtElement) declarer));
                int[] ownerLn = AstGraphUtils.lines((CtElement) declarer);
                declarer.getFormalCtTypeParameters().forEach(tp -> {
                    String tpId = AstGraphUtils.typeParamId(ownerId, tp);
                    int[] tpLn = AstGraphUtils.lines(tp);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            AstGraphUtils.extractSource(sourceLines, file, tpLn[0], tpLn[1], tp), "", null));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_TYPE_PARAM, tpId, file, ownerLn[0]));
                    if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                        var sc = tp.getSuperclass();
                        if (sc != null && !sc.getQualifiedName().equals("java.lang.Object"))
                            graph.addEdge(new GraphEdge(tpId, EdgeKind.HAS_BOUND,
                                    sc.getQualifiedName(), file, tpLn[0]));
                        tp.getSuperInterfaces().forEach(b -> graph.addEdge(
                                new GraphEdge(tpId, EdgeKind.HAS_BOUND,
                                        b.getQualifiedName(), file, tpLn[0])));
                    }
                });
            });
        }

        // ─ 4. Method nodes ──────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = AstGraphUtils.typeMemberExecId(m); if (id == null) return;
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(m));
            int[] ln = AstGraphUtils.lines(m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(m.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            //noinspection unchecked
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup ->
                    graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                            AstGraphUtils.typeMemberExecId((CtTypeMember) sup), file, ln[0])));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ─ 5. Constructor nodes ────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = AstGraphUtils.typeMemberExecId(c); if (id == null) return;
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(c));
            int[] ln = AstGraphUtils.lines(c);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], c), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(c.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
        });

        // ─ 6. Field nodes ──────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = AstGraphUtils.fieldId(field); if (id == null) return;
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(field));
            int[] ln = AstGraphUtils.lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], field), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(field.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ─ 7. Local variable + parameter nodes ─────────────────────────────
        model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = AstGraphUtils.varId(v); if (id == null) return;
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(v));
            int[] ln = AstGraphUtils.lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], v), "", null));
        });

        // ─ 8. ANNOTATION_ATTRIBUTE nodes ──────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = AstGraphUtils.qualifiedName(ann); if (annoId == null) return;
                String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(ann));
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods =
                        (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName();
                    int[] ln = AstGraphUtils.lines(m);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m), "", null));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]));
                });
            });
        }

        // ─ 9. Lambda nodes ───────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(lambda));
            int[] ln = AstGraphUtils.lines(lambda);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "λ",
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], lambda), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                CtMethod<?> em = lambda.getParent(CtMethod.class);
                if (em != null) {
                    String enc = AstGraphUtils.typeMemberExecId(em);
                    if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0]));
                } else {
                    CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                    if (ec != null) {
                        String enc = AstGraphUtils.typeMemberExecId(ec);
                        if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0]));
                    }
                }
            }
        });

        // ─ 10. INVOKES ──────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INVOKES))
            model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = AstGraphUtils.nearestExecId(inv); if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        AstGraphUtils.execRefIdForChainedInvocation(inv),
                        AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(inv)),
                        AstGraphUtils.posLine(inv)));
            });

        // ─ 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ─────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = AstGraphUtils.nearestExecId(cc); if (callerId == null) return;
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId,
                            AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(cc)),
                            AstGraphUtils.posLine(cc)));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            AstGraphUtils.execRefId(cc.getExecutable(), cc),
                            AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(cc)),
                            AstGraphUtils.posLine(cc)));
            });

        // ─ 12. REFERENCES_METHOD ────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = AstGraphUtils.nearestExecId(ref); if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        AstGraphUtils.execRefId(ref.getExecutable(), ref),
                        AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(ref)),
                        AstGraphUtils.posLine(ref)));
            });

        // ─ 13. READS_FIELD / WRITES_FIELD ─────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD))
            model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                String execId = AstGraphUtils.nearestExecId(fa); if (execId == null) return;
                var ref = fa.getVariable();
                String fId = ref.getDeclaringType() != null
                        ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                        : "?." + ref.getSimpleName();
                EdgeKind ek = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, fId,
                            AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(fa)),
                            AstGraphUtils.posLine(fa)));
            });

        // ─ 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ─────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR))
            model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String execId = AstGraphUtils.nearestExecId(va); if (execId == null) return;
                String vId = AstGraphUtils.varRefId(va.getVariable());
                EdgeKind ek = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, vId,
                            AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(va)),
                            AstGraphUtils.posLine(va)));
            });

        // ─ 15. THROWS ─────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.THROWS))
            model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String execId = AstGraphUtils.nearestExecId(thr); if (execId == null) return;
                CtExpression<?> thrown = thr.getThrownExpression();
                String typeId = (thrown instanceof CtConstructorCall<?> cc
                        && cc.getExecutable().getDeclaringType() != null)
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(thr)),
                        AstGraphUtils.posLine(thr)));
            });

        // ─ 16. REFERENCES_TYPE ────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = AstGraphUtils.nearestExecId(ta); if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        AstGraphUtils.relPath(root, AstGraphUtils.sourceFile(ta)),
                        AstGraphUtils.posLine(ta)));
            });

        log.info("AST graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }
}
