package com.example.mrrag.graph;

import com.example.mrrag.app.config.EdgeKindConfig;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.graph.model.*;
import com.example.mrrag.graph.raw.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBuilderImpl implements GraphBuilder {

    private final EdgeKindConfig edgeConfig;
    private final ProjectGraphCacheStore cacheStore;
    private final Map<ProjectKey, ProjectGraph> keyCache = new ConcurrentHashMap<>();

    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider provider, boolean force) {
        ProjectKey key = provider.projectKey();
        log.info("buildGraph: key={}", key);

        ProjectGraph cached = keyCache.get(key);
        if (cached != null && !force) {
            log.debug("buildGraph: in-memory cache hit for {}", key);
            return cached;
        }

        var loaded = cacheStore.tryLoadAllSegments(key);
        if (loaded.isPresent() && !force) {
            ProjectGraph main = loaded.get().getOrDefault(GraphSegmentIds.MAIN, new ProjectGraph());
            keyCache.put(key, main);
            log.debug("buildGraph: disk cache hit for {}", key);
            return main;
        }

        log.info("buildGraph: building from source via provider: {}",
                provider.getClass().getSimpleName());
        List<ProjectSource> sources = provider.getSources();
        log.info("Provider supplied {} .java files", sources.size());

        ProjectGraph graph = doBuildGraphFromSources(
                sources, provider.localProjectRoot().orElse(null));

        keyCache.put(key, graph);

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

    @Override
    public void invalidate(ProjectKey key) {
        keyCache.remove(key);
        log.debug("invalidate: evicted in-memory entry for {}", key);
        cacheStore.delete(key);
        log.debug("invalidate: deleted disk cache for {}", key);
    }

    private ProjectGraph doBuildGraphFromSources(List<ProjectSource> sources, Path classpathRoot) {
        if (sources.isEmpty()) {
            log.warn("doBuildGraphFromSources: empty source list, returning empty graph");
            return new ProjectGraph();
        }

        int nThreads = 1;
        List<List<ProjectSource>> batches = SourceBatchPartitioner.partition(sources, nThreads);

        log.info("doBuildGraphFromSources: {} files -> {} import-aware batches (threads={})",
                sources.size(), batches.size(), nThreads);

        List<CompletableFuture<ProjectGraph>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(
                        () -> buildBatch(batch, classpathRoot),
                        ForkJoinPool.commonPool()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        futures.forEach(f ->
                log.info("batch graph: {} nodes, {} edge-sources",
                        f.join().nodes.size(), f.join().edgesFrom.size()));

        ProjectGraph merged = new ProjectGraph();
        futures.stream()
                .map(CompletableFuture::join)
                .forEach(partial -> mergeGraphs(merged, partial));

        log.info("doBuildGraphFromSources: merged graph — {} nodes, {} edge-sources, root={}",
                merged.nodes.size(), merged.edgesFrom.size(), classpathRoot);
        return merged;
    }

    private ProjectGraph buildBatch(List<ProjectSource> sources, Path classpathRoot) {
        Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
        for (ProjectSource src : sources) {
            sourceLines.put(src.path(), src.content().split("\n", -1));
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);
        launcher.getEnvironment().setLevel("ERROR");
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) {
        }

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

    private static void mergeGraphs(ProjectGraph target, ProjectGraph source) {
        source.nodes.values().forEach(target::addNode);
        source.edgesFrom.forEach((caller, edges) ->
                edges.forEach(target::addEdge));
    }


    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model, Map<String, String[]> sourceLines, Path projectRoot) {
        var graph = new ProjectGraph();
        Set<String> repoPaths = sourceLines.keySet();

        List<Runnable> passes = new ArrayList<>();

        passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = AstGraphUtils.qualifiedName(type);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(type, sourceLines, projectRoot);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                    : (type instanceof CtInterface) ? NodeKind.INTERFACE
                    : NodeKind.CLASS;
            graph.addNode(new GraphNodeImpl(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], type, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, type.getPosition(),
                            id, kind, type.getSimpleName(), ln[0], ln[1])));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> {
                    int[] lines = AstGraphUtils.lines(ann, sourceLines, projectRoot);
                    graph.addEdge(new GraphEdge(
                            id, EdgeKind.ANNOTATED_WITH,
                            ann.getAnnotationType().getQualifiedName(), file, lines[0], lines[1]));
                });
        }));

        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = AstGraphUtils.qualifiedName(type);
                if (id == null) return;
                String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
                if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                    if (type instanceof CtClass<?> cls && cls.getSuperclass() != null) {
                        int[] lines = AstGraphUtils.lines(cls, sourceLines, projectRoot);

                        graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                cls.getSuperclass().getQualifiedName(), file, lines[0], lines[0]));
                    }
                    if (type instanceof CtInterface<?> iface) {
                        iface.getSuperInterfaces().forEach(s -> {
                            int[] lines = AstGraphUtils.lines(s, sourceLines, projectRoot);
                            graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                    s.getQualifiedName(), file, lines[0], lines[0]));
                        });
                    }
                }
                if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS) && type instanceof CtClass<?> cls)
                    cls.getSuperInterfaces().forEach(i -> {
                        int[] lines = AstGraphUtils.lines(i, sourceLines, projectRoot);
                        graph.addEdge(new GraphEdge(id, EdgeKind.IMPLEMENTS,
                                i.getQualifiedName(), file, lines[0], lines[0]));
                    });
            }));
        }

        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = AstGraphUtils.formalDeclarerId(declarer);
                if (ownerId == null) return;
                String file = AstGraphUtils.graphFilePath(declarer, projectRoot, repoPaths);
                declarer.getFormalCtTypeParameters().forEach(tp -> {
                    int[] tpLn = AstGraphUtils.lines(tp, sourceLines, projectRoot);
                    String tpId = AstGraphUtils.typeParamId(ownerId, tp);
                    graph.addNode(new GraphNodeImpl(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            AstGraphUtils.extractSource(sourceLines, file, tpLn[0], tpLn[1], tp, projectRoot),
                            AstGraphUtils.declarationOf(sourceLines, file, projectRoot, tp.getPosition(),
                                    tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(), tpLn[0], tpLn[1])));

                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_TYPE_PARAM, tpId, file, tpLn[0], tpLn[1]));

                    if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                        var sc = tp.getSuperclass();
                        if (sc != null && !sc.getQualifiedName().equals("java.lang.Object"))
                            graph.addEdge(new GraphEdge(tpId, EdgeKind.HAS_BOUND,
                                    sc.getQualifiedName(), file, tpLn[0], tpLn[1]));
                        tp.getSuperInterfaces().forEach(b -> graph.addEdge(
                                new GraphEdge(tpId, EdgeKind.HAS_BOUND,
                                        b.getQualifiedName(), file, tpLn[0], tpLn[0])));
                    }
                });
            }));
        }

        passes.add(() -> model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = AstGraphUtils.typeMemberExecId(m);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(m, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(m, sourceLines, projectRoot);
            graph.addNode(new GraphNodeImpl(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, m.getPosition(),
                            id, NodeKind.METHOD, m.getSimpleName(), ln[0], ln[1])));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(m.getDeclaringType());
                int startLine = ln[0];
                int endLine = AstGraphUtils.lines(m.getBody(), sourceLines, projectRoot)[0];
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, startLine, endLine));
            }
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup -> {
                int[] lines = AstGraphUtils.lines((CtTypeMember) sup, sourceLines, projectRoot);
                graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                        AstGraphUtils.typeMemberExecId((CtTypeMember) sup), file, lines[0], lines[1]));
            });
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> {
                    int[] lines = AstGraphUtils.lines(ann, sourceLines, projectRoot);
                    graph.addEdge(new GraphEdge(id, EdgeKind.ANNOTATED_WITH,
                            ann.getAnnotationType().getQualifiedName(), file, lines[0], lines[1]));
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = AstGraphUtils.typeMemberExecId(c);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(c, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(c, sourceLines, projectRoot);
            graph.addNode(new GraphNodeImpl(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], c, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, c.getPosition(),
                            id, NodeKind.CONSTRUCTOR, c.getSimpleName(), ln[0], ln[1])));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                int startLine = ln[0];
                int endLine = AstGraphUtils.lines(c.getBody(), sourceLines, projectRoot)[0];
                String cls = AstGraphUtils.qualifiedName(c.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, startLine, endLine));
            }
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = AstGraphUtils.fieldId(field);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(field, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(field, sourceLines, projectRoot);
            graph.addNode(new GraphNodeImpl(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], field, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, field.getPosition(),
                            id, NodeKind.FIELD, field.getSimpleName(), ln[0], ln[1])));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(field.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0], ln[1]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> {
                    int[] lines = AstGraphUtils.lines(ann, sourceLines, projectRoot);
                    graph.addEdge(new GraphEdge(
                            id, EdgeKind.ANNOTATED_WITH,
                            ann.getAnnotationType().getQualifiedName(), file, lines[0], lines[1]));
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = AstGraphUtils.varId(v);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(v, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(v, sourceLines, projectRoot);
            graph.addNode(new GraphNodeImpl(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], v, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, v.getPosition(),
                            id, NodeKind.VARIABLE, v.getSimpleName(), ln[0], ln[1])));
        }));

        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = AstGraphUtils.qualifiedName(ann);
                if (annoId == null) return;
                String file = AstGraphUtils.graphFilePath(ann, projectRoot, repoPaths);
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods =
                        (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName();
                    int[] ln = AstGraphUtils.lines(m, sourceLines, projectRoot);
                    graph.addNode(new GraphNodeImpl(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], m, projectRoot),
                            AstGraphUtils.declarationOf(sourceLines, file, projectRoot, m.getPosition(),
                                    attrId, NodeKind.ANNOTATION_ATTRIBUTE, m.getSimpleName(), ln[0], ln[1])));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0], ln[1]));
                });
            }));
        }

        passes.add(() -> model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = AstGraphUtils.graphFilePath(lambda, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(lambda, sourceLines, projectRoot);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNodeImpl(id, NodeKind.LAMBDA, "\u03bb",
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1], lambda, projectRoot),
                    AstGraphUtils.declarationOf(sourceLines, file, projectRoot, lambda.getPosition(),
                            id, NodeKind.LAMBDA, "\u03bb", ln[0], ln[1])));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                CtMethod<?> em = lambda.getParent(CtMethod.class);
                if (em != null) {
                    String enc = AstGraphUtils.typeMemberExecId(em);
                    if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0], ln[1]));
                } else {
                    CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                    if (ec != null) {
                        String enc = AstGraphUtils.typeMemberExecId(ec);
                        if (enc != null) graph.addEdge(new GraphEdge(enc, EdgeKind.DECLARES, id, file, ln[0], ln[1]));
                    }
                }
            }
        }));

        if (edgeConfig.isEnabled(EdgeKind.INVOKES))
            passes.add(() -> model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = AstGraphUtils.nearestExecId(inv);
                if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(inv, projectRoot, repoPaths);
                int[] lines = AstGraphUtils.lines(inv, sourceLines, projectRoot);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        AstGraphUtils.execRefIdForChainedInvocation(inv),
                        file, lines[0], lines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = AstGraphUtils.nearestExecId(cc);
                if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(cc, projectRoot, repoPaths);
                int[] lines = AstGraphUtils.lines(cc, sourceLines, projectRoot);
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId, file, lines[0], lines[1]));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            AstGraphUtils.execRefId(cc.getExecutable(), cc), file, lines[0], lines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = AstGraphUtils.nearestExecId(ref);
                if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(ref, projectRoot, repoPaths);
                int[] lines = AstGraphUtils.lines(ref, sourceLines, projectRoot);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        AstGraphUtils.execRefId(ref.getExecutable(), ref),
                        file, lines[0], lines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                String execId = AstGraphUtils.nearestExecId(fa);
                if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(fa, projectRoot, repoPaths);
                var ref = fa.getVariable();
                String fId = ref.getDeclaringType() != null
                        ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                        : "?." + ref.getSimpleName();
                EdgeKind ek = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(ek)) {
                    int[] lines = AstGraphUtils.lines(fa, sourceLines, projectRoot);
                    graph.addEdge(new GraphEdge(execId, ek, fId, file, lines[0], lines[1]));
                }
            }));

        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR))
            passes.add(() -> model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String execId = AstGraphUtils.nearestExecId(va);
                if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(va, projectRoot, repoPaths);
                String vId = AstGraphUtils.varRefId(va.getVariable());
                EdgeKind ek = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(ek)) {
                    int[] lines = AstGraphUtils.lines(va, sourceLines, projectRoot);
                    graph.addEdge(new GraphEdge(execId, ek, vId, file, lines[0], lines[1]));
                }
            }));

        if (edgeConfig.isEnabled(EdgeKind.THROWS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String execId = AstGraphUtils.nearestExecId(thr);
                if (execId == null) return;
                String file = AstGraphUtils.graphFilePath(thr, projectRoot, repoPaths);
                CtExpression<?> thrown = thr.getThrownExpression();
                String typeId = (thrown instanceof CtConstructorCall<?> cc
                        && cc.getExecutable().getDeclaringType() != null)
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                int[] lines = AstGraphUtils.lines(thr, sourceLines, projectRoot);
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        file, lines[0], lines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            passes.add(() -> model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = AstGraphUtils.nearestExecId(ta);
                if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                String file = AstGraphUtils.graphFilePath(ta, projectRoot, repoPaths);
                int[] lines = AstGraphUtils.lines(ta, sourceLines, projectRoot);
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        file, lines[0], lines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.HAS_IMPORT)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtCompilationUnit.class)).forEach(cu -> {
                String file = cu.getFile() != null
                        ? AstGraphUtils.graphFilePath(cu.getMainType(), projectRoot, repoPaths)
                        : "";
                String ownerId = cu.getMainType() != null
                        ? AstGraphUtils.qualifiedName(cu.getMainType())
                        : file;
                if (ownerId == null || ownerId.isBlank()) return;

                cu.getImports().forEach(imp -> {
                    String ref = imp.getReference().toString();
                    String importId = "import@" + file + ":" + ref;
                    int[] lines = AstGraphUtils.lines(imp, sourceLines, projectRoot);
                    CtImportKind kind = imp.getImportKind();
                    boolean isStatic = kind == CtImportKind.METHOD
                            || kind == CtImportKind.FIELD
                            || kind == CtImportKind.ALL_STATIC_MEMBERS;
                    String snippet = "import " + (isStatic ? "static " : "") + ref + ";";
                    graph.addNode(new GraphNodeImpl(
                            importId, NodeKind.IMPORT, ref,
                            file, lines[0], lines[1],
                            snippet,
                            new GraphNodeDeclaration(importId, NodeKind.IMPORT, ref,
                                    file, lines[0], lines[1], snippet)));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_IMPORT, importId, file, lines[0], lines[1]));
                });
            }));
        }

        passes.add(() -> {
            List<CtElement> candidates = new ArrayList<>();
            candidates.addAll(model.getElements(new TypeFilter<>(CtType.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtMethod.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtConstructor.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtField.class)));

            candidates.forEach(el -> {
                el.getComments().stream()
                        .filter(c -> c instanceof spoon.reflect.code.CtJavaDoc)
                        .map(c -> (spoon.reflect.code.CtJavaDoc) c)
                        .forEach(javadoc -> {
                            String file = AstGraphUtils.graphFilePath(el, projectRoot, repoPaths);
                            String ownerId = switch (el) {
                                case CtType<?> t -> AstGraphUtils.qualifiedName(t);
                                case CtMethod<?> m -> AstGraphUtils.typeMemberExecId(m);
                                case CtConstructor<?> c -> AstGraphUtils.typeMemberExecId(c);
                                case CtField<?> f -> AstGraphUtils.fieldId(f);
                                default -> null;
                            };
                            if (ownerId == null) return;

                            String javadocId = "javadoc@" + ownerId;
                            String rawText = javadoc.getContent();
                            int dot = rawText.indexOf('.');
                            String summary = dot >= 0 ? rawText.substring(0, dot + 1).strip() : rawText.strip();
                            int[] ln = AstGraphUtils.lines(javadoc, sourceLines, projectRoot);

                            graph.addNode(new GraphNodeImpl(
                                    javadocId, NodeKind.JAVADOC, summary,
                                    file, ln[0], ln[1],
                                    rawText,
                                    new GraphNodeDeclaration(javadocId, NodeKind.JAVADOC, summary,
                                            file, ln[0], ln[1], summary)));
                            graph.addEdge(new GraphEdge(
                                    ownerId, EdgeKind.HAS_JAVADOC, javadocId, file, ln[0], ln[1]));
                        });
            });
        });

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
