package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.model.graph.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p><b>Nodes:</b> CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
 * LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES,
 * INSTANTIATES_ANONYMOUS, REFERENCES_METHOD, READS_FIELD, WRITES_FIELD,
 * READS_LOCAL_VAR, WRITES_LOCAL_VAR, THROWS, ANNOTATED_WITH,
 * REFERENCES_TYPE, OVERRIDES, HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
 */
@Slf4j
@Service
public class AstGraphService implements AstGraphProvider{

    // ------------------------------------------------------------------
    // Dependencies & cache
    // ------------------------------------------------------------------

    private final EdgeKindConfig edgeConfig;
    private final Map<String, ProjectGraph> cache = new ConcurrentHashMap<>();

    public AstGraphService(EdgeKindConfig edgeConfig) {
        this.edgeConfig = edgeConfig;
    }


    @Override
    public ProjectGraph buildGraph(String projectId, List<String> source) {
        return cache.computeIfAbsent(projectId, root -> doBuildGraph(projectId, source));
    }


    public ProjectGraph buildGraph(Path projectRoot) {
        return cache.computeIfAbsent(projectRoot, this::doBuildGraph);
    }

    @Override
    public void invalidate(String projectId) {
        cache.remove(projectId);
    }

    // ------------------------------------------------------------------
    // Path normalisation
    // ------------------------------------------------------------------

    /**
     * Translates a path coming from a GitLab diff (e.g.
     * {@code gl-hooks/src/main/java/com/example/Foo.java}) into the
     * relative path stored inside the graph (e.g.
     * {@code src/main/java/com/example/Foo.java}).
     *
     * <p>Strategy: walk through all file paths that the graph actually
     * contains and return the first one that the incoming path ends with
     * (or is equal to). This handles both mono-repo sub-module prefixes
     * and cases where the paths are already identical.
     *
     * @param diffPath  path as reported by GitLab diff
     * @param graph     the project graph to look up known paths in
     * @return the matching graph-relative path, or {@code diffPath} unchanged
     *         when no suffix match is found
     */
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

    // ------------------------------------------------------------------
    // Graph construction
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraph(String projectId, Map<String, String[]> sourceLines) {
        log.info("Building Spoon AST graph for {}", projectId);
        try {
            log.info("Source roots for Spoon: {}", sourceRoots);
            Launcher launcher = new Launcher();
            sourceRoots.forEach(launcher::addInputResource);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setCommentEnabled(false);
            launcher.getEnvironment().setAutoImports(false);
            try {
                launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
            } catch (NoSuchMethodError ignored) {}

            CtModel model;
            try {
                model = launcher.buildModel();
            } catch (ModelBuildingException mbe) {
                log.warn("Spoon ModelBuildingException for {} — using partial model. Cause: {}",
                        projectId, mbe.getMessage());
                model = launcher.getModel();
                if (model == null) {
                    log.error("Spoon returned null model for {}, returning empty graph", projectRoot);
                    return new ProjectGraph();
                }
            }

            // Read source files into memory once for verbatim snippet extraction.
            // Key: relative path (same as stored in GraphNode.filePath).
            Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
            String root = projectRoot.toString();
            for (String srcRoot : sourceRoots) {
                Path srcRootPath = Path.of(srcRoot);
                if (!Files.isDirectory(srcRootPath)) continue;
                try (Stream<Path> walk = Files.walk(srcRootPath)) {
                    walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            String rel = relPath(root, p.toAbsolutePath().toString());
                            try {
                                String[] lines = Files.readString(p, StandardCharsets.UTF_8).split("\n", -1);
                                sourceLines.put(rel, lines);
                            } catch (IOException e) {
                                log.warn("Cannot read source file {}: {}", p, e.getMessage());
                            }
                        });
                }
            }

            var graph = new ProjectGraph();

            // ── 1. Type nodes ────────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type);
                if (id == null) return;
                String file = relPath(root, sourceFile(type));
                int[] ln = lines(type);

                NodeKind kind;
                if (type instanceof CtAnnotationType) {
                    kind = NodeKind.ANNOTATION;
                } else if (type instanceof CtInterface) {
                    kind = NodeKind.INTERFACE;
                } else {
                    kind = NodeKind.CLASS;
                }

                graph.addNode(new GraphNode(id, kind, type.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], type)));

                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    type.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
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
                        if (type instanceof CtClass<?> cls) {
                            var superRef = cls.getSuperclass();
                            if (superRef != null)
                                graph.addEdge(new GraphEdge(
                                        id, EdgeKind.EXTENDS, superRef.getQualifiedName(), file, ln[0]
                                ));
                        } else if (type instanceof CtInterface<?> iface) {
                            iface.getSuperInterfaces().forEach(superRef ->
                                    graph.addEdge(new GraphEdge(
                                            id, EdgeKind.EXTENDS, superRef.getQualifiedName(), file, ln[0]
                                    ))
                            );
                        }
                    }
                    if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                        if (type instanceof CtClass<?> cls) {
                            cls.getSuperInterfaces().forEach(ifRef ->
                                    graph.addEdge(new GraphEdge(
                                            id, EdgeKind.IMPLEMENTS, ifRef.getQualifiedName(),
                                            file, ln[0]
                                    ))
                            );
                        }
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
                        graph.addEdge(new GraphEdge(
                                ownerId, EdgeKind.HAS_TYPE_PARAM, tpId,
                                file, ownerLn[0]
                        ));

                        if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                            CtTypeReference<?> superCls = tp.getSuperclass();
                            if (superCls != null && !superCls.getQualifiedName().equals("java.lang.Object")) {
                                graph.addEdge(new GraphEdge(
                                        tpId, EdgeKind.HAS_BOUND,
                                        superCls.getQualifiedName(), file, tpLn[0]));
                            }
                            tp.getSuperInterfaces().forEach(bound ->
                                    graph.addEdge(new GraphEdge(
                                            tpId, EdgeKind.HAS_BOUND,
                                            bound.getQualifiedName(), file, tpLn[0])));
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
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], m)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(m.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
                }
                //noinspection unchecked
                m.getTopDefinitions().stream().findFirst()
                        .filter(top -> top != m)
                        .ifPresent(superId ->
                                graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                                        typeMemberExecId((CtTypeMember) superId), file, ln[0])));
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    m.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
                }
            });

            // ── 5. Constructor nodes ────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
                String id = typeMemberExecId(c);
                if (id == null) return;
                String file = relPath(root, sourceFile(c));
                int[] ln = lines(c);
                graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], c)));

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
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], field)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(field.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
                }
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    field.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
                }
            });

            // ── 7. Local variable + parameter nodes ───────────────────────────────────
            model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
                if (v instanceof CtField) return;
                String id = varId(v);
                if (id == null) return;
                String file = relPath(root, sourceFile(v));
                int[] ln = lines(v);
                graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], v)));
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
                        graph.addEdge(new GraphEdge(
                                annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]
                        ));
                    });
                });
            }

            // ── 9. Lambda nodes ────────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
                String file = relPath(root, sourceFile(lambda));
                int[] ln = lines(lambda);
                String id = "lambda@" + file + ":" + ln[0];
                graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], lambda)));

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
                    graph.addEdge(new GraphEdge(
                            callerId, EdgeKind.INVOKES, execRefId(inv.getExecutable()),
                            relPath(root, sourceFile(inv)), posLine(inv)
                    ));
                });
            }

            // ── 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ───────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS)) {
                model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                    String callerId = nearestExecId(cc);
                    if (callerId == null) return;
                    String typeId = cc.getExecutable().getDeclaringType() != null
                            ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                    boolean isAnon = cc instanceof CtNewClass;
                    EdgeKind kind = isAnon ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                callerId, kind, typeId,
                                relPath(root, sourceFile(cc)), posLine(cc)
                        ));
                    if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                        graph.addEdge(new GraphEdge(
                                callerId, EdgeKind.INVOKES, execRefId(cc.getExecutable()),
                                relPath(root, sourceFile(cc)), posLine(cc)
                        ));
                    }
                });
            }

            // ── 12. REFERENCES_METHOD ─────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD)) {
                model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                    String callerId = nearestExecId(ref);
                    if (callerId == null) return;
                    graph.addEdge(new GraphEdge(
                            callerId, EdgeKind.REFERENCES_METHOD, execRefId(ref.getExecutable()),
                            relPath(root, sourceFile(ref)), posLine(ref)
                    ));
                });
            }

            // ── 13. READS_FIELD / WRITES_FIELD ────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD)) {
                model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                    String execId = nearestExecId(fa);
                    if (execId == null) return;
                    CtFieldReference<?> ref = fa.getVariable();
                    String fId = ref.getDeclaringType() != null
                            ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                            : "?." + ref.getSimpleName();
                    EdgeKind kind = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                execId, kind, fId,
                                relPath(root, sourceFile(fa)), posLine(fa)
                        ));
                });
            }

            // ── 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ─────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR)) {
                model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                    if (va instanceof CtFieldAccess) return;
                    String execId = nearestExecId(va);
                    if (execId == null) return;
                    String vId = varRefId(va.getVariable());
                    EdgeKind kind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                execId, kind, vId,
                                relPath(root, sourceFile(va)), posLine(va)
                        ));
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
                            && cc.getExecutable().getDeclaringType() != null) {
                        typeId = cc.getExecutable().getDeclaringType().getQualifiedName();
                    }
                    graph.addEdge(new GraphEdge(
                            execId, EdgeKind.THROWS, typeId,
                            relPath(root, sourceFile(thr)), posLine(thr)
                    ));
                });
            }

            // ── 16. REFERENCES_TYPE ───────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE)) {
                model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                    String execId = nearestExecId(ta);
                    if (execId == null) return;
                    if (ta.getAccessedType() == null) return;
                    graph.addEdge(new GraphEdge(
                            execId, EdgeKind.REFERENCES_TYPE, ta.getAccessedType().getQualifiedName(),
                            relPath(root, sourceFile(ta)), posLine(ta)
                    ));
                });
            }

            log.info("Spoon graph built: {} nodes, {} edge-sources",
                    graph.nodes.size(), graph.edgesFrom.size());
            return graph;

        } catch (IOException e) {
            throw new RuntimeException("Failed to collect source roots for " + projectRoot, e);
        }
    }

    // ------------------------------------------------------------------
    // Source snippet helpers
    // ------------------------------------------------------------------

    /**
     * Returns verbatim original source lines for {@code startLine..endLine} when
     * the file is present in {@code sourceLines}; falls back to Spoon
     * {@code toString()} otherwise (external/synthetic nodes).
     *
     * <p>When the fallback is used, {@code startLine} should already be {@code -1}
     * (set by {@link #lines(CtElement)} for elements without a valid position),
     * so callers can distinguish external snippets from project snippets.
     *
     * @param sourceLines map of relative file path → source lines (1-based index = array[line-1])
     * @param filePath    relative path of the file
     * @param startLine   first line, 1-based; {@code -1} or {@code 0} triggers fallback
     * @param endLine     last line, 1-based
     * @param el          Spoon element used as fallback
     * @return non-null source text
     */
    private static String extractSource(Map<String, String[]> sourceLines,
                                        String filePath, int startLine, int endLine,
                                        CtElement el) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = sourceLines.get(filePath);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to   = Math.min(lines.length, endLine);
                if (from < to) {
                    return String.join("\n", Arrays.copyOfRange(lines, from, to));
                }
            }
        }
        // fallback: Spoon pretty-print (external dependency or missing file)
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
            if (member instanceof CtExecutable<?> exec) {
                return owner + "#" + exec.getSignature();
            }
            return owner + "#" + member.getSimpleName();
        } catch (Exception e) {
            return null;
        }
    }

    private String execRefId(CtExecutableReference<?> ref) {
        if (ref == null) return "unresolved";
        try {
            String owner = ref.getDeclaringType() != null
                    ? ref.getDeclaringType().getQualifiedName() : "?";
            return owner + "#" + ref.getSignature();
        } catch (Exception e) {
            return "unresolved:" + ref.getSimpleName();
        }
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
            if (pos.isValidPosition() && pos.getFile() != null)
                return pos.getFile().getAbsolutePath();
        } catch (Exception ignored) {}
        return "";
    }

    private String relPath(String root, String abs) {
        if (abs.isEmpty()) return "unknown";
        return abs.startsWith(root)
                ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    /**
     * Returns {@code [startLine, endLine]} for the element, both 1-based.
     * Returns {@code [-1, -1]} when position information is unavailable
     * (external or synthetic node — no source file in this project).
     */
    private int[] lines(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition())
                return new int[]{ pos.getLine(), pos.getEndLine() };
        } catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    private int posLine(CtElement el) {
        try {
            var pos = el.getPosition();
            return pos.isValidPosition() ? pos.getLine() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

}
