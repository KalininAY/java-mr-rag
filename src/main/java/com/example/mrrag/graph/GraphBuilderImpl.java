package com.example.mrrag.graph;

import com.example.mrrag.app.config.EdgeKindConfig;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.graph.model.*;
import com.example.mrrag.graph.raw.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.*;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p>All ID computation, snippet extraction, and path-normalisation helpers
 * are delegated to {@link AstGraphUtils}.
 *
 * <p>The only public entry point for graph construction is
 * {@link #buildGraph(ProjectSourceProvider)}. The {@link ProjectKey} that
 * drives cache lookups is supplied by the provider itself via
 * {@link ProjectSourceProvider#projectKey()} — callers never construct keys
 * manually.
 *
 * <p>Performance notes:
 * <ul>
 *   <li>Sources are split into {@code N} batches ({@code N = availableProcessors}).
 *       Each batch is parsed by an independent {@link Launcher} instance running
 *       in parallel via {@link ForkJoinPool#commonPool()}.  Partial
 *       {@link ProjectGraph}s are merged after all batches complete.
 *       Because {@link GraphEdge} stores callee as a qualified-name string,
 *       cross-batch edges are automatically valid after the merge.</li>
 *   <li>The independent {@code model.getElements()} passes in
 *       {@link #doBuildGraphFromModel} also run in parallel via
 *       {@link ForkJoinPool#commonPool()}.</li>
 *   <li>{@link com.example.mrrag.app.service.ProjectGraphCacheStore#savePartitioned}
 *       is dispatched asynchronously so the caller receives the graph
 *       immediately while the disk write happens in the background.</li>
 *   <li>{@link ProjectGraph} uses {@code ConcurrentHashMap} /
 *       {@code CopyOnWriteArrayList} and is safe for concurrent writes.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphBuilderImpl implements GraphBuilder {

    // ------------------------------------------------------------------
    // Dependencies & caches
    // ------------------------------------------------------------------

    private final EdgeKindConfig         edgeConfig;
    private final ProjectGraphCacheStore cacheStore;

    /** In-memory cache keyed by {@link ProjectKey} (path + fingerprint). */
    private final Map<ProjectKey, ProjectGraph> keyCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Primary entry point (GraphBuilder interface)
    // ------------------------------------------------------------------

    /**
     * Builds (or returns a cached) {@link ProjectGraph} for the given provider.
     *
     * <p>Cache lookup order:
     * <ol>
     *   <li>In-memory {@code keyCache}.</li>
     *   <li>Disk {@code cacheStore}.</li>
     *   <li>Build from sources, then populate both caches.</li>
     * </ol>
     */
    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        ProjectKey key = provider.projectKey();
        log.info("buildGraph: key={}", key);

        // 1. in-memory cache
        ProjectGraph cached = keyCache.get(key);
        if (cached != null) {
            log.debug("buildGraph: in-memory cache hit for {}", key);
            return cached;
        }

        // 2. disk cache
        var loaded = cacheStore.tryLoadAllSegments(key);
        if (loaded.isPresent()) {
            ProjectGraph main = loaded.get().getOrDefault(GraphSegmentIds.MAIN, new ProjectGraph());
            keyCache.put(key, main);
            log.debug("buildGraph: disk cache hit for {}", key);
            return main;
        }

        // 3. build from sources
        log.info("buildGraph: building from source via provider: {}",
                provider.getClass().getSimpleName());
        List<ProjectSource> sources = provider.getSources();
        log.info("Provider supplied {} .java files", sources.size());

        ProjectGraph graph = doBuildGraphFromSources(
                sources, provider.localProjectRoot().orElse(null));

        keyCache.put(key, graph);

        // async disk save — return the graph to caller immediately
        Map<String, ProjectGraph> segments = new LinkedHashMap<>();
        segments.put(GraphSegmentIds.MAIN, graph);
        CompletableFuture.runAsync(() -> {
            try {
                cacheStore.savePartitioned(key, segments);
                log.debug("buildGraph: saved to disk cache for {}", key);
            } catch (Exception e) {
                log.warn("buildGraph: failed to save cache for {}: {}", key, e.getMessage());
            }
        });

        return graph;
    }

    // ------------------------------------------------------------------
    // Invalidation
    // ------------------------------------------------------------------

    @Override
    public void invalidate(ProjectKey key) {
        keyCache.remove(key);
        log.debug("invalidate: evicted in-memory entry for {}", key);
        cacheStore.delete(key);
        log.debug("invalidate: deleted disk cache for {}", key);
    }

    // ------------------------------------------------------------------
    // Core: sources → Spoon model  (parallel batches)
    // ------------------------------------------------------------------

    /**
     * Splits {@code sources} into {@code N} batches ({@code N = availableProcessors}),
     * builds a {@link CtModel} for each batch in parallel, then merges the resulting
     * partial {@link ProjectGraph}s into a single graph.
     *
     * <p>Cross-batch edges are preserved automatically because {@link GraphEdge}
     * stores the callee as a fully-qualified name string — after the merge both
     * endpoint nodes are present in the combined graph.
     */
    private ProjectGraph doBuildGraphFromSources(List<ProjectSource> sources, Path classpathRoot) {
        if (sources.isEmpty()) {
            log.warn("doBuildGraphFromSources: empty source list, returning empty graph");
            return new ProjectGraph();
        }

        int nThreads  = Math.max(1, Runtime.getRuntime().availableProcessors());
        int batchSize = Math.max(1, (int) Math.ceil((double) sources.size() / nThreads));
        List<List<ProjectSource>> batches = partition(sources, batchSize);

        log.info("doBuildGraphFromSources: {} files -> {} batches (batchSize={})",
                sources.size(), batches.size(), batchSize);

        // Build one partial graph per batch in parallel
        List<CompletableFuture<ProjectGraph>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(
                        () -> buildBatch(batch, classpathRoot),
                        ForkJoinPool.commonPool()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Merge all partial graphs into one
        ProjectGraph merged = new ProjectGraph();
        futures.stream()
               .map(CompletableFuture::join)
               .forEach(partial -> mergeGraphs(merged, partial));

        log.info("doBuildGraphFromSources: merged graph — {} nodes, {} edge-sources",
                merged.nodes.size(), merged.edgesFrom.size());
        return merged;
    }

    /**
     * Builds a {@link ProjectGraph} for a single batch of source files.
     * Each call creates an independent {@link Launcher} instance, so batches
     * can safely run concurrently.
     */
    private ProjectGraph buildBatch(List<ProjectSource> sources, Path classpathRoot) {
        Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
        for (ProjectSource src : sources) {
            sourceLines.put(src.path(), src.content().split("\n", -1));
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setLevel("ERROR");
        launcher.getEnvironment().setComplianceLevel(17);
        try { launcher.getEnvironment().setIgnoreDuplicateDeclarations(true); }
        catch (NoSuchMethodError ignored) {}

        for (ProjectSource src : sources) {
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        CtModel model;
        try {
            model = launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException (batch of {} files) — using partial model. Cause: {}",
                    sources.size(), mbe.getMessage());
            model = launcher.getModel();
            if (model == null) {
                log.error("Spoon returned null model for batch — returning empty partial graph");
                return new ProjectGraph();
            }
        }
        return doBuildGraphFromModel(model, sourceLines, classpathRoot);
    }

    /**
     * Merges {@code source} into {@code target} in-place.
     * Both collections are concurrent-safe, so this is safe to call
     * from any thread after all batch futures have completed.
     */
    private static void mergeGraphs(ProjectGraph target, ProjectGraph source) {
        source.nodes.values().forEach(target::addNode);
        source.edgesFrom.forEach((caller, edges) ->
                edges.forEach(target::addEdge));
    }

    /**
     * Partitions {@code list} into sub-lists of at most {@code size} elements.
     * Equivalent to Guava's {@code Lists.partition} (no Guava dependency needed).
     */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Core: Spoon model → ProjectGraph  (parallel passes)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model, Map<String, String[]> sourceLines, Path projectRoot) {
        var graph = new ProjectGraph();
        Set<String> repoPaths = sourceLines.keySet();

        // Each Runnable is one independent pass over the Spoon model.
        // ProjectGraph is thread-safe (ConcurrentHashMap + CopyOnWriteArrayList),
        // and CtModel is read-only after buildModel() completes, so all passes
        // can safely run in parallel.
        List<Runnable> passes = new ArrayList<>();

        // Pass 1: types (CLASS / INTERFACE / ANNOTATION)
        passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = AstGraphUtils.qualifiedName(type); if (id == null) return;
            String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(type);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                          : (type instanceof CtInterface)      ? NodeKind.INTERFACE
                          :                                      NodeKind.CLASS;
            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], type, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, type.getPosition()), null));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        }));

        // Pass 2: EXTENDS / IMPLEMENTS
        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = AstGraphUtils.qualifiedName(type); if (id == null) return;
                String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
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
            }));
        }

        // Pass 3: HAS_TYPE_PARAM / HAS_BOUND
        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = AstGraphUtils.formalDeclarerId(declarer); if (ownerId == null) return;
                String file = AstGraphUtils.graphFilePath(declarer, projectRoot, repoPaths);
                int[] ownerLn = AstGraphUtils.lines(declarer);
                declarer.getFormalCtTypeParameters().forEach(tp -> {
                    int[] tpLn = AstGraphUtils.lines(tp);
                    String tpId = AstGraphUtils.typeParamId(ownerId, tp);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            AstGraphUtils.extractSource(sourceLines, file, tpLn[0], tpLn[1], tp, projectRoot),
                            AstGraphUtils.declarationOf(sourceLines, file, projectRoot, tp.getPosition()), null));
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
            }));
        }

        // Pass 4: methods
        passes.add(() -> model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = AstGraphUtils.typeMemberExecId(m); if (id == null) return;
            String file = AstGraphUtils.graphFilePath(m, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, m.getPosition()), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(m.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup ->
                    graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                            AstGraphUtils.typeMemberExecId((CtTypeMember) sup), file, ln[0])));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        }));

        // Pass 5: constructors
        passes.add(() -> model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = AstGraphUtils.typeMemberExecId(c); if (id == null) return;
            String file = AstGraphUtils.graphFilePath(c, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(c);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], c, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, c.getPosition()), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(c.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
        }));

        // Pass 6: fields
        passes.add(() -> model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = AstGraphUtils.fieldId(field); if (id == null) return;
            String file = AstGraphUtils.graphFilePath(field, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], field, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, field.getPosition()), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(field.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> graph.addEdge(new GraphEdge(
                        id, EdgeKind.ANNOTATED_WITH,
                        ann.getAnnotationType().getQualifiedName(), file, ln[0])));
        }));

        // Pass 7: local variables
        passes.add(() -> model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = AstGraphUtils.varId(v); if (id == null) return;
            String file = AstGraphUtils.graphFilePath(v, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], v, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, v.getPosition()), null));
        }));

        // Pass 8: annotation attributes
        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = AstGraphUtils.qualifiedName(ann); if (annoId == null) return;
                String file = AstGraphUtils.graphFilePath(ann, projectRoot, repoPaths);
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods =
                        (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName();
                    int[] ln = AstGraphUtils.lines(m);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m, projectRoot),
                            AstGraphUtils.declarationOf(sourceLines, file, projectRoot, m.getPosition()), null));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]));
                });
            }));
        }

        // Pass 9: lambdas
        passes.add(() -> model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = AstGraphUtils.graphFilePath(lambda, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(lambda);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], lambda, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, lambda.getPosition()), null));
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
        }));

        // Pass 10: invocations
        if (edgeConfig.isEnabled(EdgeKind.INVOKES))
            passes.add(() -> model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = AstGraphUtils.nearestExecId(inv); if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(inv, projectRoot, repoPaths);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        AstGraphUtils.execRefIdForChainedInvocation(inv),
                        file, AstGraphUtils.posLine(inv)));
            }));

        // Pass 11: constructor calls
        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = AstGraphUtils.nearestExecId(cc); if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(cc, projectRoot, repoPaths);
                int line = AstGraphUtils.posLine(cc);
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId, file, line));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            AstGraphUtils.execRefId(cc.getExecutable(), cc), file, line));
            }));

        // Pass 12: method references
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = AstGraphUtils.nearestExecId(ref); if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(ref, projectRoot, repoPaths);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        AstGraphUtils.execRefId(ref.getExecutable(), ref),
                        file, AstGraphUtils.posLine(ref)));
            }));

        // Pass 13: field reads/writes
        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                String execId = AstGraphUtils.nearestExecId(fa); if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(fa, projectRoot, repoPaths);
                var ref = fa.getVariable();
                String fId = ref.getDeclaringType() != null
                        ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                        : "?." + ref.getSimpleName();
                EdgeKind ek = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, fId, file, AstGraphUtils.posLine(fa)));
            }));

        // Pass 14: local var reads/writes
        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR))
            passes.add(() -> model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String execId = AstGraphUtils.nearestExecId(va); if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(va, projectRoot, repoPaths);
                String vId = AstGraphUtils.varRefId(va.getVariable());
                EdgeKind ek = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(execId, ek, vId, file, AstGraphUtils.posLine(va)));
            }));

        // Pass 15: throws
        if (edgeConfig.isEnabled(EdgeKind.THROWS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String execId = AstGraphUtils.nearestExecId(thr); if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(thr, projectRoot, repoPaths);
                CtExpression<?> thrown = thr.getThrownExpression();
                String typeId = (thrown instanceof CtConstructorCall<?> cc
                        && cc.getExecutable().getDeclaringType() != null)
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        file, AstGraphUtils.posLine(thr)));
            }));

        // Pass 16: type references
        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            passes.add(() -> model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = AstGraphUtils.nearestExecId(ta); if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                String file = AstGraphUtils.graphFilePath(ta, projectRoot, repoPaths);
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        file, AstGraphUtils.posLine(ta)));
            }));

        // Run all passes in parallel and wait for completion
        log.info("doBuildGraphFromModel: running {} passes in parallel via ForkJoinPool", passes.size());
        try {
            ForkJoinPool.commonPool()
                    .submit(() -> passes.parallelStream().forEach(Runnable::run))
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("AST graph build interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("AST graph build failed in parallel pass", e.getCause());
        }

        log.info("AST graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }
}
