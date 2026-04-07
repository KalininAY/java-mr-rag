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
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p>Implements {@link GraphBuilder}: accepts any {@link ProjectSourceProvider}
 * so graph construction is fully decoupled from how source files are obtained
 * (local clone, GitLab API, test fixtures, …).
 *
 * <p><b>Nodes:</b> CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
 * LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES,
 * INSTANTIATES_ANONYMOUS, REFERENCES_METHOD, READS_FIELD, WRITES_FIELD,
 * READS_LOCAL_VAR, WRITES_LOCAL_VAR, THROWS, ANNOTATED_WITH,
 * REFERENCES_TYPE, OVERRIDES, HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
 *
 * <p>{@link GraphNode#bodyHash()} is computed automatically inside the
 * {@code GraphNode} compact constructor — no explicit hash argument is needed
 * here; callers pass {@code null} and the record handles it.
 */
@Slf4j
@Component
public class GraphBuilderImpl implements GraphBuilder {

    // ------------------------------------------------------------------
    // Dependencies & cache
    // ------------------------------------------------------------------

    private final EdgeKindConfig          edgeConfig;
    private final ProjectGraphCacheStore cacheStore;

    private final Map<Path, ProjectGraph>        localCache = new ConcurrentHashMap<>();
    private final Map<ProjectKey, ProjectGraph>  keyCache   = new ConcurrentHashMap<>();

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
        log.debug("invalidate(ProjectKey): evicted in-memory entry for {}", key);
    }

    // ------------------------------------------------------------------
    // Primary entry point
    // ------------------------------------------------------------------

    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        log.info("Building AST graph via provider: {}", provider.getClass().getSimpleName());
        List<ProjectSource> sources = provider.getSources();
        log.info("Provider supplied {} .java files", sources.size());
        return doBuildGraphFromSources(sources, provider.localProjectRoot().orElse(null));
    }

    @Override
    public void invalidate(Path projectRoot) {
        localCache.remove(projectRoot);
    }

    // ------------------------------------------------------------------
    // Path normalisation
    // ------------------------------------------------------------------

    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String norm = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (norm.equals(knownNorm))          return known;
            if (norm.endsWith("/" + knownNorm))  return known;
            if (knownNorm.endsWith("/" + norm))  return known;
        }
        String[] parts = norm.split("/");
        for (int i = 1; i < parts.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            if (graph.byFile.containsKey(candidate)) return candidate;
        }
        log.debug("normalizeFilePath: no match in graph for '{}'", diffPath);
        return diffPath;
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
    // NOTE: all new GraphNode(...) calls pass null as the last (bodyHash)
    // argument — the GraphNode compact constructor recomputes it from
    // sourceSnippet / declarationSnippet / id automatically.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model,
                                               Map<String, String[]> sourceLines,
                                               String root) {
        var graph = new ProjectGraph();

        // ── 1. Type nodes ────────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = qualifiedName(type); if (id == null) return;
            String file = relPath(root, sourceFile(type)); int[] ln = lines(type);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                          : (type instanceof CtInterface)      ? NodeKind.INTERFACE
                          :                                      NodeKind.CLASS;
            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    extractSource(sourceLines, file, ln[0], ln[1], type), "", null));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ── 2. EXTENDS / IMPLEMENTS ──────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type); if (id == null) return;
                String file = relPath(root, sourceFile(type)); int[] ln = lines(type);
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

        // ── 3. TYPE_PARAM nodes ──────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = formalDeclarerId(declarer); if (ownerId == null) return;
                String file = relPath(root, sourceFile((CtElement) declarer));
                int[] ownerLn = lines((CtElement) declarer);
                declarer.getFormalCtTypeParameters().forEach(tp -> {
                    String tpId = typeParamId(ownerId, tp); int[] tpLn = lines(tp);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            extractSource(sourceLines, file, tpLn[0], tpLn[1], tp), "", null));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_TYPE_PARAM, tpId, file, ownerLn[0]));
                    if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                        CtTypeReference<?> sc = tp.getSuperclass();
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

        // ── 4. Method nodes ───────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = typeMemberExecId(m); if (id == null) return;
            String file = relPath(root, sourceFile(m)); int[] ln = lines(m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], m), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = qualifiedName(m.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            //noinspection unchecked
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup ->
                    graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                            typeMemberExecId((CtTypeMember) sup), file, ln[0])));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ── 5. Constructor nodes ──────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = typeMemberExecId(c); if (id == null) return;
            String file = relPath(root, sourceFile(c)); int[] ln = lines(c);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], c), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = qualifiedName(c.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
        });

        // ── 6. Field nodes ────────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = fieldId(field); if (id == null) return;
            String file = relPath(root, sourceFile(field)); int[] ln = lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], field), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = qualifiedName(field.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        });

        // ── 7. Local variable + parameter nodes ───────────────────────────────────
        model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = varId(v); if (id == null) return;
            String file = relPath(root, sourceFile(v)); int[] ln = lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1], extractSource(sourceLines, file, ln[0], ln[1], v), "", null));
        });

        // ── 8. ANNOTATION_ATTRIBUTE nodes ─────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = qualifiedName(ann); if (annoId == null) return;
                String file = relPath(root, sourceFile(ann));
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods =
                        (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName(); int[] ln = lines(m);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            extractSource(sourceLines, file, ln[0], ln[1], m), "", null));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]));
                });
            });
        }

        // ── 9. Lambda nodes ───────────────────────────────────────────────────────
        model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = relPath(root, sourceFile(lambda)); int[] ln = lines(lambda);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                    file, ln[0], ln[1],
                    extractSource(sourceLines, file, ln[0], ln[1], lambda), "", null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                CtMethod<?> em = lambda.getParent(CtMethod.class);
                if (em != null) {
                    String enc = typeMemberExecId(em);
                    if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0]));
                } else {
                    CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                    if (ec != null) {
                        String enc = typeMemberExecId(ec);
                        if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0]));
                    }
                }
            }
        });

        // ── 10. INVOKES ───────────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INVOKES))
            model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = nearestExecId(inv); if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        execRefIdForChainedInvocation(inv),
                        relPath(root, sourceFile(inv)), posLine(inv)));
            });

        // ── 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = nearestExecId(cc); if (callerId == null) return;
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId,
                            relPath(root, sourceFile(cc)), posLine(cc)));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            execRefId(cc.getExecutable(), cc),
                            relPath(root, sourceFile(cc)), posLine(cc)));
            });

        // ── 12. REFERENCES_METHOD ─────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = nearestExecId(ref); if (callerId == null) return;
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        execRefId(ref.getExecutable(), ref),
                        relPath(root, sourceFile(ref)), posLine(ref)));
            });

        // ── 13. READS_FIELD / WRITES_FIELD ───────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD))
            model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                String execId = nearestExecId(fa); if (execId == null) return;
                CtFieldReference<?> ref = fa.getVariable();
                String fId = ref.getDeclaringType() != null
                        ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                        : "?." + ref.getSimpleName();
                EdgeKind ek = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, fId,
                            relPath(root, sourceFile(fa)), posLine(fa)));
            });

        // ── 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ───────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR))
            model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String execId = nearestExecId(va); if (execId == null) return;
                String vId = varRefId(va.getVariable());
                EdgeKind ek = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, vId,
                            relPath(root, sourceFile(va)), posLine(va)));
            });

        // ── 15. THROWS ────────────────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.THROWS))
            model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String execId = nearestExecId(thr); if (execId == null) return;
                CtExpression<?> thrown = thr.getThrownExpression();
                String typeId = (thrown instanceof CtConstructorCall<?> cc
                        && cc.getExecutable().getDeclaringType() != null)
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        relPath(root, sourceFile(thr)), posLine(thr)));
            });

        // ── 16. REFERENCES_TYPE ──────────────────────────────────────────────────
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = nearestExecId(ta); if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        relPath(root, sourceFile(ta)), posLine(ta)));
            });

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
                if (from < to) return String.join("\n", Arrays.copyOfRange(lines, from, to));
            }
        }
        return snippet(el);
    }

    private static String snippet(CtElement el) {
        try { String s = el.toString(); return s != null ? s : ""; } catch (Exception e) { return ""; }
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
            CtType<?> decl = member.getDeclaringType();
            String owner = decl != null ? decl.getQualifiedName() : "?";
            if (member instanceof CtConstructor<?>)
                return constructorExecutableId(owner, ((CtExecutable<?>) member).getSignature());
            if (member instanceof CtExecutable<?> exec)
                return owner + "#" + exec.getSignature();
            return owner + "#" + member.getSimpleName();
        } catch (Exception e) { return null; }
    }

    private static String constructorExecutableId(String ownerQualified, String signature) {
        if (signature == null || signature.isBlank()) return ownerQualified + "#<init>()";
        int open = signature.indexOf('(');
        return open < 0 ? ownerQualified + "#<init>()" : ownerQualified + "#<init>" + signature.substring(open);
    }

    private static String qualifiedExecutableOwner(CtExecutableReference<?> ref, CtElement useSite) {
        try {
            if (ref.getDeclaringType() != null) {
                String q = ref.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) {}
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl instanceof CtTypeMember tm && tm.getDeclaringType() != null) {
                String q = tm.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) {}
        if (useSite instanceof CtInvocation inv) { String r = inferOwnerFromInvocation(inv); if (r != null) return r; }
        if (useSite instanceof CtConstructorCall<?> cc) { String r = inferOwnerFromConstructorCall(cc); if (r != null) return r; }
        if (useSite instanceof CtExecutableReferenceExpression ere) { String r = inferOwnerFromExecutableReferenceExpression(ere); if (r != null) return r; }
        return "?";
    }

    private static boolean isUsableQualifiedName(String q) {
        return q != null && !q.isBlank() && !"?".equals(q);
    }

    private static String inferOwnerFromInvocation(CtInvocation<?> inv) {
        CtExpression<?> target = inv.getTarget();
        if (target instanceof CtTypeAccess<?> ta) {
            try { if (ta.getAccessedType() != null) { String q = ta.getAccessedType().getQualifiedName(); if (isUsableQualifiedName(q)) return q; } } catch (Exception ignored) {}
        }
        if (target != null) {
            try { CtTypeReference<?> t = target.getType(); if (t != null) { String q = t.getQualifiedName(); if (isUsableQualifiedName(q)) return q; } } catch (Exception ignored) {}
        }
        return null;
    }

    private static String inferOwnerFromConstructorCall(CtConstructorCall<?> cc) {
        try { if (cc.getType() != null) { String q = cc.getType().getQualifiedName(); if (isUsableQualifiedName(q)) return q; } } catch (Exception ignored) {}
        return null;
    }

    private static String inferOwnerFromExecutableReferenceExpression(CtExecutableReferenceExpression<?, ?> ere) {
        try {
            CtExpression<?> target = ere.getTarget();
            if (target instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) { String q = ta.getAccessedType().getQualifiedName(); if (isUsableQualifiedName(q)) return q; }
            if (target != null) { CtTypeReference<?> t = target.getType(); if (t != null) { String q = t.getQualifiedName(); if (isUsableQualifiedName(q)) return q; } }
        } catch (Exception ignored) {}
        return null;
    }

    private String execRefId(CtExecutableReference<?> ref, CtElement useSite) {
        if (ref == null) return "unresolved";
        try {
            String owner = qualifiedExecutableOwner(ref, useSite);
            String sig = ref.getSignature();
            return ref.isConstructor() ? constructorExecutableId(owner, sig) : owner + "#" + sig;
        } catch (Exception e) { return "unresolved:" + ref.getSimpleName(); }
    }

    private String execRefIdForChainedInvocation(CtInvocation<?> inv) {
        String base = execRefId(inv.getExecutable(), inv);
        if (!base.startsWith("?#")) return base;
        String suffix = base.substring(2);
        CtExpression<?> target = inv.getTarget();
        if (target instanceof CtInvocation<?> inner) {
            String innerId = execRefIdForChainedInvocation(inner);
            if (!innerId.startsWith("?")) return innerId + "#" + suffix;
        }
        if (target != null) {
            try { CtTypeReference<?> t = target.getType(); if (t != null) { String q = t.getQualifiedName(); if (isUsableQualifiedName(q)) return q + "#" + suffix; } } catch (Exception ignored) {}
        }
        return base;
    }

    private String fieldId(CtField<?> field) {
        if (field.getDeclaringType() == null) return null;
        return field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName();
    }

    private String varId(CtVariable<?> v) {
        if (!v.getPosition().isValidPosition()) return null;
        String file = v.getPosition().getFile() != null ? v.getPosition().getFile().getName() : "?";
        return "var@" + file + ":" + v.getPosition().getLine() + ":" + v.getSimpleName();
    }

    private String varRefId(CtVariableReference<?> ref) {
        if (ref == null) return "var@?";
        try { CtVariable<?> d = ref.getDeclaration(); if (d != null) return varId(d); } catch (Exception ignored) {}
        return "var@" + ref.getSimpleName();
    }

    private String typeParamId(String ownerId, CtTypeParameter tp) {
        return ownerId + "#<" + tp.getSimpleName() + ">";
    }

    private String formalDeclarerId(CtFormalTypeDeclarer d) {
        if (d instanceof CtType<?> t) return qualifiedName(t);
        if (d instanceof CtTypeMember m) return typeMemberExecId(m);
        return null;
    }

    private String nearestExecId(CtElement el) {
        CtMethod<?> m = el.getParent(CtMethod.class);
        if (m != null) return typeMemberExecId(m);
        CtConstructor<?> c = el.getParent(CtConstructor.class);
        return c != null ? typeMemberExecId(c) : null;
    }

    // ------------------------------------------------------------------
    // Position / path helpers
    // ------------------------------------------------------------------

    private String sourceFile(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition()) {
                if (pos.getFile() != null) return pos.getFile().getAbsolutePath();
                var cu = pos.getCompilationUnit();
                if (cu != null) {
                    String f = cu.getFile() != null ? cu.getFile().getPath()
                            : (cu.getMainType() != null
                               ? cu.getMainType().getQualifiedName().replace('.', '/') + ".java" : "");
                    if (!f.isEmpty()) return f;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String relPath(String root, String abs) {
        if (abs.isEmpty()) return "unknown";
        if (root.isEmpty()) return abs;
        return abs.startsWith(root) ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    private int[] lines(CtElement el) {
        try { var p = el.getPosition(); if (p.isValidPosition()) return new int[]{ p.getLine(), p.getEndLine() }; }
        catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    private int posLine(CtElement el) {
        try { var p = el.getPosition(); return p.isValidPosition() ? p.getLine() : -1; }
        catch (Exception e) { return -1; }
    }
}
