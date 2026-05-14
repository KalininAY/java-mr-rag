package com.example.mrrag.graph;

import com.example.mrrag.app.config.EdgeKindConfig;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSource;
import com.example.mrrag.graph.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.FileSystemFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphBuilder {

    private final EdgeKindConfig edgeConfig;

    public ProjectGraph buildGraph(ProjectSourceProvider provider) {
        ProjectKey key = provider.projectKey();
        log.info("GraphBuilder: key={}, building from source via provider: {}", key, provider.getClass().getSimpleName());

        List<ProjectSource> sources = provider.getSources();
        if (sources.isEmpty()) {
            log.warn("GraphBuilder: empty source list, returning empty graph");
            return new ProjectGraph();
        }

        log.info("Provider supplied {} .java files", sources.size());

        int nThreads = 1;
        List<List<ProjectSource>> batches = SourceBatchPartitioner.partition(sources, nThreads);

        log.info("GraphBuilder: {} files -> {} import-aware batches (threads={})",
                sources.size(), batches.size(), nThreads);

        List<CompletableFuture<ProjectGraph>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(
                        () -> buildBatch(batch, provider.localProjectRoot().orElse(null)),
                        ForkJoinPool.commonPool()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        futures.forEach(f ->
                log.info("GraphBuilder: batch {} nodes, {} edge-sources",
                        f.join().nodes.size(), f.join().edgesFrom.size()));

        ProjectGraph merged = new ProjectGraph();
        futures.stream()
                .map(CompletableFuture::join)
                .forEach(partial -> mergeGraphs(merged, partial));

        log.info("doBuildGraphFromSources: merged graph — {} nodes, {} edge-sources, root={}",
                merged.nodes.size(), merged.edgesFrom.size(), provider.localProjectRoot().orElse(null));
        return merged;
    }

    public ProjectGraph buildBatch(List<ProjectSource> sources, Path classpathRoot) {
        Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
        for (ProjectSource src : sources) {
            sourceLines.put(src.path(), src.content().split("\n", -1));
        }

        Launcher launcher = new Launcher();
        Environment environment = launcher.getEnvironment();
        environment.setNoClasspath(true);
        environment.setCommentEnabled(true);
        environment.setAutoImports(false);
        environment.setIgnoreSyntaxErrors(true);
        environment.setLevel("ERROR");
        environment.setIgnoreDuplicateDeclarations(true);

        sources.stream()
                .map(it -> new FileSystemFile(classpathRoot.resolve(it.path()).toFile()))
                .forEach(launcher::addInputResource);

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

    public void mergeGraphs(ProjectGraph target, ProjectGraph source) {
        source.nodes.values().forEach(target::addNode);
        source.edgesFrom.forEach((caller, edges) -> edges.forEach(target::addEdge));
    }

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraphFromModel(CtModel model, Map<String, String[]> sourceLines, Path projectRoot) {
        var graph = new ProjectGraph();
        Set<String> repoPaths = sourceLines.keySet();

        List<Runnable> passes = new ArrayList<>();

        passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            if (type instanceof CtTypeParameter) return;

            String id = AstGraphUtils.qualifiedName(type);
            String filePath = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(type, sourceLines);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                    : (type instanceof CtInterface) ? NodeKind.INTERFACE
                    : NodeKind.CLASS;
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, type);

            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), filePath, lines[0], lines[1],
                    sourceSnippet, declarationSnippet, null));

            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    int[] annLines = AstGraphUtils.lines(ann, sourceLines);
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION, ann.getAnnotationType().getSimpleName(),
                            "", 0, 0, null, "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(id, EdgeKind.ANNOTATED_WITH, annId, filePath, annLines[0], annLines[1]));
                });

            if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                if (type instanceof CtClass<?> cls && cls.getSuperclass() != null) {
                    String calleeId = AstGraphUtils.qualifiedName(cls.getSuperclass());
                    int[] declarationLinesCallee = AstGraphUtils.declarationLines(cls.getSuperclass(), sourceLines);
                    graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS, calleeId, filePath, declarationLinesCallee[0], declarationLinesCallee[0]));
                }
                if (type instanceof CtInterface<?> iface) {
                    iface.getSuperInterfaces().forEach(interfaceType -> {
                        String calleeId = AstGraphUtils.qualifiedName(interfaceType);
                        int[] declarationLinesCallee = AstGraphUtils.declarationLines(interfaceType, sourceLines);
                        graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS, calleeId, filePath, declarationLinesCallee[0], declarationLinesCallee[0]));
                    });
                }
            }

            if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS) && type instanceof CtClass<?> cls)
                cls.getSuperInterfaces().forEach(interfaceType -> {
                    String calleeId = AstGraphUtils.qualifiedName(interfaceType);
                    int[] declarationLinesCallee = AstGraphUtils.declarationLines(interfaceType, sourceLines);
                    graph.addEdge(new GraphEdge(id, EdgeKind.IMPLEMENTS, calleeId, filePath, declarationLinesCallee[0], declarationLinesCallee[0]));
                });
        }));

        if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                String ownerId = AstGraphUtils.formalDeclarerId(declarer);
                String filePath = AstGraphUtils.graphFilePath(declarer, projectRoot, repoPaths);
                declarer.getFormalCtTypeParameters().forEach(typeParameter -> {
                    int[] tpLn = AstGraphUtils.lines(typeParameter, sourceLines);
                    String tpId = AstGraphUtils.typeParamId(ownerId, typeParameter);
                    String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, tpLn[0], tpLn[1]);
                    String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, typeParameter);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, typeParameter.getSimpleName(),
                            filePath, tpLn[0], tpLn[1], sourceSnippet, declarationSnippet, null));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_TYPE_PARAM, tpId, filePath, tpLn[0], tpLn[1]));
                    if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                        var typeParameterSuperclass = typeParameter.getSuperclass();
                        if (typeParameterSuperclass != null && !typeParameterSuperclass.getQualifiedName().equals("java.lang.Object"))
                            graph.addEdge(new GraphEdge(tpId, EdgeKind.HAS_BOUND, typeParameterSuperclass.getQualifiedName(), filePath, tpLn[0], tpLn[1]));
                        typeParameter.getSuperInterfaces().forEach(typeParamSuperInt ->
                                graph.addEdge(new GraphEdge(tpId, EdgeKind.HAS_BOUND, typeParamSuperInt.getQualifiedName(), filePath, tpLn[0], tpLn[0])));
                    }
                });
            }));
        }

        passes.add(() -> model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = AstGraphUtils.typeMemberExecId(m);
            String filePath = AstGraphUtils.graphFilePath(m, projectRoot, repoPaths);
            int[] methodLines = AstGraphUtils.lines(m, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, methodLines[0], methodLines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    filePath, methodLines[0], methodLines[1], sourceSnippet, declarationSnippet, null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String callerId = AstGraphUtils.qualifiedName(m.getDeclaringType());
                int[] declarationLines = AstGraphUtils.declarationLines(m, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.DECLARES, id, filePath, declarationLines[0], declarationLines[1]));
            }
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup -> {
                int[] lines = AstGraphUtils.lines((CtTypeMember) sup, sourceLines);
                graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES, AstGraphUtils.typeMemberExecId((CtTypeMember) sup), filePath, lines[0], lines[1]));
            });
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    int[] annLines = AstGraphUtils.lines(ann, sourceLines);
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION, ann.getAnnotationType().getSimpleName(),
                            "", 0, 0, null, "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(id, EdgeKind.ANNOTATED_WITH, annId, filePath, annLines[0], annLines[1]));
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(ctor -> {
            String id = AstGraphUtils.typeMemberExecId(ctor);
            String filePath = AstGraphUtils.graphFilePath(ctor, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(ctor, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, ctor);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, ctor.getSimpleName(),
                    filePath, lines[0], lines[1], sourceSnippet, declarationSnippet, null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String callerId = AstGraphUtils.qualifiedName(ctor.getDeclaringType());
                int[] declarationLines = AstGraphUtils.declarationLines(ctor, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.DECLARES, id, filePath, declarationLines[0], declarationLines[1]));
            }
        }));

        // ----------------------------------------------------------------
        // Pass: CtAnonymousExecutable — static { ... } и instance { ... }
        //
        // ID строится через AstGraphUtils.anonExecId — тот же метод,
        // что вызывает nearestExecId, поэтому ID узла и ID caller-а
        // рёбер гарантированно совпадают.
        //
        // putIfAbsent: несколько static { } в одном классе дают
        // одинаковый #<clinit> — создаём узел только один раз.
        // ----------------------------------------------------------------
        passes.add(() -> model.getElements(new TypeFilter<>(CtAnonymousExecutable.class)).forEach(anon -> {
            if (anon.getDeclaringType() == null) return;

            String id = AstGraphUtils.anonExecId(anon);
            boolean isStatic = anon.getModifiers().contains(ModifierKind.STATIC);
            String simpleName = isStatic ? "<clinit>" : "<init_block>";

            String filePath = AstGraphUtils.graphFilePath(anon, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(anon, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, anon);

            graph.nodes.putIfAbsent(id, new GraphNode(
                    id, NodeKind.INIT_BLOCK, simpleName,
                    filePath, lines[0], lines[1], sourceSnippet, declarationSnippet, null));

            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String ownerName = anon.getDeclaringType().getQualifiedName();
                graph.addEdge(new GraphEdge(ownerName, EdgeKind.DECLARES, id, filePath, lines[0], lines[1]));
            }
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtField.class)).forEach(typeField -> {
            if (typeField.getDeclaringType() instanceof CtAnnotationType) return;

            String id = AstGraphUtils.fieldId(typeField);
            String filePath = AstGraphUtils.graphFilePath(typeField, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(typeField, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, typeField);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, typeField.getSimpleName(),
                    filePath, lines[0], lines[1], sourceSnippet, declarationSnippet, null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String callerId = AstGraphUtils.qualifiedName(typeField.getDeclaringType());
                graph.addEdge(new GraphEdge(callerId, EdgeKind.DECLARES, id, filePath, lines[0], lines[1]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                typeField.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    int[] annLines = AstGraphUtils.lines(ann, sourceLines);
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION, ann.getAnnotationType().getSimpleName(),
                            "", 0, 0, null, "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(id, EdgeKind.ANNOTATED_WITH, annId, filePath, annLines[0], annLines[1]));
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = AstGraphUtils.varId(v);
            String filePath = AstGraphUtils.graphFilePath(v, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(v, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    filePath, lines[0], lines[1], sourceSnippet, declarationSnippet, null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES) && v instanceof CtParameter<?>) {
                CtExecutable<?> owner = v.getParent(CtExecutable.class);
                if (owner instanceof CtTypeMember tm) {
                    String callerId = AstGraphUtils.typeMemberExecId(tm);
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.DECLARES, id, filePath, lines[0], lines[1]));
                }
            }
        }));

        if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                String annoId = AstGraphUtils.qualifiedName(ann);
                String filePath = AstGraphUtils.graphFilePath(ann, projectRoot, repoPaths);
                @SuppressWarnings("unchecked")
                Collection<CtMethod<?>> methods = (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                methods.forEach(m -> {
                    String attrId = annoId + "#" + m.getSimpleName();
                    int[] ln = AstGraphUtils.lines(m, sourceLines);
                    String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, ln[0], ln[1]);
                    String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, m);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), filePath, ln[0], ln[1], sourceSnippet, declarationSnippet, null));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, filePath, ln[0], ln[1]));
                });
            }));
        }

        passes.add(() -> model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String filePath = AstGraphUtils.graphFilePath(lambda, projectRoot, repoPaths);
            int[] lines = AstGraphUtils.lines(lambda, sourceLines);
            String sourceSnippet = AstGraphUtils.extractSource(sourceLines, filePath, lines[0], lines[1]);
            String declarationSnippet = AstGraphUtils.declarationOf(sourceLines, filePath, lambda);
            CtMethod<?> em = lambda.getParent(CtMethod.class);
            String encId = em != null ? AstGraphUtils.typeMemberExecId(em) : filePath;
            List<CtLambda<?>> siblings = em != null
                    ? em.getElements(new TypeFilter<>(CtLambda.class)) : List.of(lambda);
            int idx = siblings.indexOf(lambda);
            String id = "lambda@" + encId + "#" + idx;
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                    filePath, lines[0], lines[1], sourceSnippet, declarationSnippet, null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                if (em != null) {
                    graph.addEdge(new GraphEdge(AstGraphUtils.typeMemberExecId(em), EdgeKind.DECLARES, id, filePath, lines[0], lines[1]));
                } else {
                    CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                    if (ec != null)
                        graph.addEdge(new GraphEdge(AstGraphUtils.typeMemberExecId(ec), EdgeKind.DECLARES, id, filePath, lines[0], lines[1]));
                }
            }
        }));

        if (edgeConfig.isEnabled(EdgeKind.INVOKES))
            passes.add(() -> model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                String callerId = AstGraphUtils.nearestExecId(inv);
                String calleeId = AstGraphUtils.execRefIdForChainedInvocation(inv);
                String filePath = AstGraphUtils.graphFilePath(inv, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(inv, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES, calleeId, filePath, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = AstGraphUtils.nearestExecId(cc);
                String filePath = AstGraphUtils.graphFilePath(cc, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(cc, sourceLines);
                String typeId = AstGraphUtils.inferOwnerFromConstructorCall(cc);
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId, filePath, declarationLines[0], declarationLines[1]));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                    String calleeId = AstGraphUtils.execRefId(cc.getExecutable(), cc);
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES, calleeId, filePath, declarationLines[0], declarationLines[1]));
                }
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = AstGraphUtils.nearestExecId(ref);
                String calleeId = AstGraphUtils.execRefId(ref.getExecutable(), ref);
                String filePath = AstGraphUtils.graphFilePath(ref, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(ref, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD, calleeId, filePath, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                // пропускаем синтетическое поле SomeType.class — оно не является реальным чтением поля
                if ("class".equals(fa.getVariable().getSimpleName())) return;
                String callerId = AstGraphUtils.nearestExecId(fa);
                String filePath = AstGraphUtils.graphFilePath(fa, projectRoot, repoPaths);
                String calleeId = AstGraphUtils.fieldId(fa.getVariable());
                EdgeKind edgeKind = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                if (edgeConfig.isEnabled(edgeKind)) {
                    int[] declarationLines = AstGraphUtils.declarationLines(fa, sourceLines);
                    graph.addEdge(new GraphEdge(callerId, edgeKind, calleeId, filePath, declarationLines[0], declarationLines[1]));
                }
            }));

        if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR))
            passes.add(() -> model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                if (va instanceof CtFieldAccess) return;
                String callerId = AstGraphUtils.nearestExecId(va);
                String file = AstGraphUtils.graphFilePath(va, projectRoot, repoPaths);
                String calleeId = AstGraphUtils.varRefId(va.getVariable(), va);
                EdgeKind edgeKind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                if (edgeConfig.isEnabled(edgeKind)) {
                    int[] declarationLines = AstGraphUtils.declarationLines(va, sourceLines);
                    graph.addEdge(new GraphEdge(callerId, edgeKind, calleeId, file, declarationLines[0], declarationLines[1]));
                }
            }));

        if (edgeConfig.isEnabled(EdgeKind.THROWS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                String callerId = AstGraphUtils.nearestExecId(thr);
                String filePath = AstGraphUtils.graphFilePath(thr, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(thr, sourceLines);
                CtExpression<?> thrown = thr.getThrownExpression();
                String calleeId = (thrown instanceof CtConstructorCall<?> cc)
                        ? AstGraphUtils.inferOwnerFromConstructorCall(cc) : null;
                if (calleeId == null) return;

                graph.addEdge(new GraphEdge(callerId, EdgeKind.THROWS, calleeId, filePath, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            passes.add(() -> model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String callerId = AstGraphUtils.nearestExecId(ta);
                String filePath = AstGraphUtils.graphFilePath(ta, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(ta, sourceLines);
                if (ta.getAccessedType() == null) return;
                String calleeId = AstGraphUtils.qualifiedName(ta.getAccessedType());
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_TYPE, calleeId, filePath, declarationLines[0], declarationLines[1]));
            }));

        passes.add(() -> {
            List<CtElement> candidates = new ArrayList<>();
            candidates.addAll(model.getElements(new TypeFilter<>(CtType.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtMethod.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtConstructor.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtField.class)));
            candidates.forEach(el -> el.getComments().stream()
                    .filter(c -> c instanceof CtJavaDoc)
                    .map(c -> (CtJavaDoc) c)
                    .forEach(javadoc -> {
                        String filePath = AstGraphUtils.graphFilePath(el, projectRoot, repoPaths);
                        String callerId = switch (el) {
                            case CtType<?> t     -> AstGraphUtils.qualifiedName(t);
                            case CtMethod<?> m   -> AstGraphUtils.typeMemberExecId(m);
                            case CtConstructor<?> c -> AstGraphUtils.typeMemberExecId(c);
                            case CtField<?> f    -> AstGraphUtils.fieldId(f);
                            default              -> "unresolved_id_java_doc";
                        };
                        String javadocId = "javadoc@" + callerId;
                        int[] lines = AstGraphUtils.lines(javadoc, sourceLines);
                        String rawText = javadoc.getContent();
                        int dot = rawText.indexOf('.');
                        String sourceSnippet = dot >= 0 ? rawText.substring(0, dot + 1).strip() : rawText.strip();
                        graph.addNode(new GraphNode(
                                javadocId, NodeKind.JAVADOC, sourceSnippet, filePath, lines[0], lines[1], rawText, sourceSnippet, null));
                        graph.addEdge(new GraphEdge(callerId, EdgeKind.HAS_JAVADOC, javadocId, filePath, lines[0], lines[1]));
                    }));
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

        log.info("AST graph built: {} nodes, {} edge-sources", graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }
}
