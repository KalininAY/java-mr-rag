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
import spoon.support.compiler.VirtualFile;

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

        int nThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
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
                .map(it-> new VirtualFile(it.content(), it.path()))
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
            int[] ln = AstGraphUtils.lines(type, sourceLines);
            NodeKind kind = (type instanceof CtAnnotationType) ? NodeKind.ANNOTATION
                    : (type instanceof CtInterface) ? NodeKind.INTERFACE
                    : NodeKind.CLASS;
            graph.addNode(new GraphNode(id, kind, type.getSimpleName(), file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, type), null));
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                type.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    int[] lines = AstGraphUtils.declarationLines(type, sourceLines);
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION,
                            ann.getAnnotationType().getSimpleName(),
                            null, 0, 0, null,
                            "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(
                            id, EdgeKind.ANNOTATED_WITH, annId, file, lines[0], lines[1]));
                    if (edgeConfig.isEnabled(EdgeKind.ANNOTATES)) {
                        int[] declarationLines = AstGraphUtils.declarationLines(ann, sourceLines);
                        graph.addEdge(new GraphEdge(
                                annId, EdgeKind.ANNOTATES, id, file, declarationLines[0], declarationLines[1]));
                    };
                });
        }));

        if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = AstGraphUtils.qualifiedName(type);
                if (id == null) return;
                String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
                if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                    if (type instanceof CtClass<?> cls && cls.getSuperclass() != null) {
                        int[] lines = AstGraphUtils.lines(cls, sourceLines);

                        graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                cls.getSuperclass().getQualifiedName(), file, lines[0], lines[0]));
                    }
                    if (type instanceof CtInterface<?> iface) {
                        iface.getSuperInterfaces().forEach(s -> {
                            int[] lines = AstGraphUtils.declarationLines(s, sourceLines);
                            graph.addEdge(new GraphEdge(id, EdgeKind.EXTENDS,
                                    s.getQualifiedName(), file, lines[0], lines[0]));
                        });
                    }
                }
                if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS) && type instanceof CtClass<?> cls)
                    cls.getSuperInterfaces().forEach(i -> {
                        int[] lines = AstGraphUtils.declarationLines(i, sourceLines);
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
                    int[] tpLn = AstGraphUtils.lines(tp, sourceLines);
                    String tpId = AstGraphUtils.typeParamId(ownerId, tp);
                    graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                            file, tpLn[0], tpLn[1],
                            AstGraphUtils.extractSource(sourceLines, file, tpLn[0], tpLn[1]),
                            AstGraphUtils.declarationOf(sourceLines, file, tp), null));

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
            int[] ln = AstGraphUtils.lines(m, sourceLines);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, m), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(m.getDeclaringType());
                int[] declarationLines = AstGraphUtils.declarationLines(m, sourceLines);
                if (cls != null)
                    graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, declarationLines[0], declarationLines[1]));
            }
            m.getTopDefinitions().stream().findFirst().filter(top -> top != m).ifPresent(sup -> {
                int[] lines = AstGraphUtils.lines((CtTypeMember) sup, sourceLines);
                graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                        AstGraphUtils.typeMemberExecId((CtTypeMember) sup), file, lines[0], lines[1]));
            });
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                m.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    int[] declarationLines = AstGraphUtils.declarationLines(m, sourceLines);
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION,
                            ann.getAnnotationType().getSimpleName(),
                            null, 0, 0, null,
                            "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(id, EdgeKind.ANNOTATED_WITH, annId, file, declarationLines[0], declarationLines[1]));
                    if (edgeConfig.isEnabled(EdgeKind.ANNOTATES)) {
                        int[] lines = AstGraphUtils.lines(ann, sourceLines);
                        graph.addEdge(new GraphEdge(annId, EdgeKind.ANNOTATES, id, file, lines[0], lines[1]));
                    }
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = AstGraphUtils.typeMemberExecId(c);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(c, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(c, sourceLines);
            graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, c), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                int[] declarationLines = AstGraphUtils.declarationLines(c, sourceLines);
                String cls = AstGraphUtils.qualifiedName(c.getDeclaringType());
                if (cls != null)
                    graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, declarationLines[0], declarationLines[1]));
            }
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            if (field.getDeclaringType() instanceof CtAnnotationType) return;
            String id = AstGraphUtils.fieldId(field);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(field, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(field, sourceLines);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, field), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                String cls = AstGraphUtils.qualifiedName(field.getDeclaringType());
                if (cls != null) graph.addEdge(new GraphEdge(cls, EdgeKind.DECLARES, id, file, ln[0], ln[1]));
            }
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH))
                field.getAnnotations().forEach(ann -> {
                    String annId = ann.getAnnotationType().getQualifiedName();
                    graph.nodes.computeIfAbsent(annId, k -> new GraphNode(
                            annId, NodeKind.ANNOTATION,
                            ann.getAnnotationType().getSimpleName(),
                            null, 0, 0, null,
                            "@" + ann.getAnnotationType().getSimpleName(), null));
                    graph.addEdge(new GraphEdge(
                            id, EdgeKind.ANNOTATED_WITH, annId, file, ln[0], ln[1]));
                    if (edgeConfig.isEnabled(EdgeKind.ANNOTATES)) {
                        int[] lines = AstGraphUtils.declarationLines(ann, sourceLines);
                        graph.addEdge(new GraphEdge(
                                annId, EdgeKind.ANNOTATES, id, file, lines[0], lines[1]));
                    }
                });
        }));

        passes.add(() -> model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = AstGraphUtils.varId(v);
            if (id == null) return;
            String file = AstGraphUtils.graphFilePath(v, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(v, sourceLines);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, v), null));
            // Add DECLARES edge from owning executable to parameter
            if (edgeConfig.isEnabled(EdgeKind.DECLARES) && v instanceof CtParameter<?>) {
                CtExecutable<?> owner = v.getParent(CtExecutable.class);
                if (owner instanceof CtTypeMember tm) {
                    String ownerId = AstGraphUtils.typeMemberExecId(tm);
                    if (ownerId != null)
                        graph.addEdge(new GraphEdge(ownerId, EdgeKind.DECLARES, id, file, ln[0], ln[1]));
                }
            }
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
                    int[] ln = AstGraphUtils.lines(m, sourceLines);
                    graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                            m.getSimpleName(), file, ln[0], ln[1],
                            AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                            AstGraphUtils.declarationOf(sourceLines, file, m), null));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0], ln[1]));
                    graph.addEdge(new GraphEdge(annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0], ln[1]));
                });
            }));
        }

        passes.add(() -> model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = AstGraphUtils.graphFilePath(lambda, projectRoot, repoPaths);
            int[] ln = AstGraphUtils.lines(lambda, sourceLines);
            CtMethod<?> em = lambda.getParent(CtMethod.class);
            String encId = em != null ? AstGraphUtils.typeMemberExecId(em) : file;

            List<CtLambda<?>> siblings = em != null
                    ? em.getElements(new TypeFilter<>(CtLambda.class)) : List.of(lambda);
            int idx = siblings.indexOf(lambda);

            String id = "lambda@" + encId + "#" + idx;
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                    file, ln[0], ln[1],
                    AstGraphUtils.extractSource(sourceLines, file, ln[0], ln[1]),
                    AstGraphUtils.declarationOf(sourceLines, file, lambda), null));
            if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
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
                int[] declarationLines = AstGraphUtils.declarationLines(inv, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                        AstGraphUtils.execRefIdForChainedInvocation(inv),
                        file, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS))
            passes.add(() -> model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                String callerId = AstGraphUtils.nearestExecId(cc);
                if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(cc, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(cc, sourceLines);
                String typeId = cc.getExecutable().getDeclaringType() != null
                        ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                EdgeKind ek = (cc instanceof CtNewClass) ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                if (edgeConfig.isEnabled(ek))
                    graph.addEdge(new GraphEdge(callerId, ek, typeId, file, declarationLines[0], declarationLines[1]));
                if (edgeConfig.isEnabled(EdgeKind.INVOKES))
                    graph.addEdge(new GraphEdge(callerId, EdgeKind.INVOKES,
                            AstGraphUtils.execRefId(cc.getExecutable(), cc), file, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD))
            passes.add(() -> model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                String callerId = AstGraphUtils.nearestExecId(ref);
                if (callerId == null) return;
                String file = AstGraphUtils.graphFilePath(ref, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(ref, sourceLines);
                graph.addEdge(new GraphEdge(callerId, EdgeKind.REFERENCES_METHOD,
                        AstGraphUtils.execRefId(ref.getExecutable(), ref),
                        file, declarationLines[0], declarationLines[1]));
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
                    int[] declarationLines = AstGraphUtils.declarationLines(fa, sourceLines);
                    graph.addEdge(new GraphEdge(execId, ek, fId, file, declarationLines[0], declarationLines[1]));
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
                    int[] declarationLines = AstGraphUtils.declarationLines(va, sourceLines);
                    graph.addEdge(new GraphEdge(execId, ek, vId, file, declarationLines[0], declarationLines[1]));
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
                int[] declarationLines = AstGraphUtils.declarationLines(thr, sourceLines);
                graph.addEdge(new GraphEdge(execId, EdgeKind.THROWS, typeId,
                        file, declarationLines[0], declarationLines[1]));
            }));

        if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE))
            passes.add(() -> model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                String execId = AstGraphUtils.nearestExecId(ta);
                if (execId == null) return;
                if (ta.getAccessedType() == null) return;
                String file = AstGraphUtils.graphFilePath(ta, projectRoot, repoPaths);
                int[] declarationLines = AstGraphUtils.declarationLines(ta, sourceLines);
                graph.addEdge(new GraphEdge(execId, EdgeKind.REFERENCES_TYPE,
                        ta.getAccessedType().getQualifiedName(),
                        file, declarationLines[0], declarationLines[1]));
            }));
        if (edgeConfig.isEnabled(EdgeKind.HAS_IMPORT)) {
            passes.add(() -> model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                var pos = type.getPosition();
                if (pos == null || !pos.isValidPosition()) return;
                CtCompilationUnit cu = pos.getCompilationUnit();
                if (cu == null) return;

                String file = AstGraphUtils.graphFilePath(type, projectRoot, repoPaths);
                String ownerId = AstGraphUtils.qualifiedName(type);
                int[] declarationLines = AstGraphUtils.declarationLines(type, sourceLines);
                if (ownerId == null || ownerId.isBlank()) return;

                cu.getImports().forEach(imp -> {
                    String ref;
                    boolean isStatic;
                    if (imp.getReference() != null) {
                        ref = imp.getReference().toString();
                        CtImportKind kind = imp.getImportKind();
                        isStatic = kind == CtImportKind.METHOD
                                || kind == CtImportKind.FIELD
                                || kind == CtImportKind.ALL_STATIC_MEMBERS;
                    } else {
                        int lineNum = imp.getPosition().isValidPosition() ? imp.getPosition().getLine() : -1;
                        ref = AstGraphUtils.parseImportRefFromSource(sourceLines, file, lineNum);
                        isStatic = AstGraphUtils.isStaticImportBySource(sourceLines, file, lineNum);
                    }
                    String importId = "import@" + file + ":" + ref;
                    int[] lines = AstGraphUtils.lines(imp, sourceLines);
                    String snippet = "import " + (isStatic ? "static " : "") + ref + ";";
                    graph.addNode(new GraphNode(
                            importId, NodeKind.IMPORT, ref,
                            file, lines[0], lines[1],
                            snippet, snippet, null));
                    graph.addEdge(new GraphEdge(ownerId, EdgeKind.HAS_IMPORT, importId, file, lines[0], lines[1]));
                    graph.addEdge(new GraphEdge(importId, EdgeKind.IMPORTS, ownerId, file, declarationLines[0], declarationLines[1]));
                });
            }));
        }

        passes.add(() -> {
            List<CtElement> candidates = new ArrayList<>();
            candidates.addAll(model.getElements(new TypeFilter<>(CtType.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtMethod.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtConstructor.class)));
            candidates.addAll(model.getElements(new TypeFilter<>(CtField.class)));

            candidates.forEach(el ->
                    el.getComments().stream()
                            .filter(c -> c instanceof CtJavaDoc)
                            .map(c -> (CtJavaDoc) c)
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
                                int[] ln = AstGraphUtils.lines(javadoc, sourceLines);

                                graph.addNode(new GraphNode(
                                        javadocId, NodeKind.JAVADOC, summary,
                                        file, ln[0], ln[1],
                                        rawText, summary, null));
                                graph.addEdge(new GraphEdge(
                                        ownerId, EdgeKind.HAS_JAVADOC, javadocId, file, ln[0], ln[1]));
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

        log.info("AST graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }
}
