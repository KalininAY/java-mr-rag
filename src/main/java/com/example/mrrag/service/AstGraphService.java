package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p><b>Nodes:</b> CLASS, CONSTRUCTOR, METHOD, FIELD, VARIABLE, LAMBDA,
 * ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES,
 * INSTANTIATES_ANONYMOUS, REFERENCES_METHOD, READS_FIELD, WRITES_FIELD,
 * READS_LOCAL_VAR, WRITES_LOCAL_VAR, THROWS, ANNOTATED_WITH,
 * REFERENCES_TYPE, OVERRIDES, HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
 */
@Slf4j
@Service
public class AstGraphService {

    // ------------------------------------------------------------------
    // Public model
    // ------------------------------------------------------------------

    /** Kinds of graph nodes. */
    public enum NodeKind {
        /** A class, interface, enum or annotation type. */
        CLASS,
        /** A constructor ({@code <init>}). */
        CONSTRUCTOR,
        /** An instance or static method. */
        METHOD,
        /** A class field. */
        FIELD,
        /** A local variable or method parameter ({@code CtVariable} that is not a field). */
        VARIABLE,
        /** A lambda expression. */
        LAMBDA,
        /** An annotation type (target of {@code ANNOTATED_WITH} edges). */
        ANNOTATION,
        /**
         * A generic type parameter declaration.
         * Examples: {@code T} in {@code class Foo<T>},
         * {@code K extends Comparable<K>} in a method signature.
         */
        TYPE_PARAM,
        /**
         * An element declared inside an {@code @interface} body.
         * Example: {@code String value() default "";}
         */
        ANNOTATION_ATTRIBUTE
    }

    /**
     * Kinds of directed graph edges.
     * Each value can be toggled via {@code graph.edge.<name>.enabled=true/false}.
     */
    public enum EdgeKind {

        // ── Structural (declaration) ──────────────────────────────────────────

        /**
         * Owner declares a child member.
         * Examples: CLASS→METHOD, CLASS→FIELD, CLASS→CONSTRUCTOR, METHOD→LAMBDA.
         */
        DECLARES,

        // ── Type hierarchy ────────────────────────────────────────────────────

        /** {@code class A extends B} → A –EXTENDS→ B */
        EXTENDS,

        /** {@code class A implements I} → A –IMPLEMENTS→ I */
        IMPLEMENTS,

        // ── Invocations ───────────────────────────────────────────────────────

        /** {@code foo.bar()} → caller –INVOKES→ callee */
        INVOKES,

        /** {@code new Foo(arg)} → caller –INSTANTIATES→ Foo */
        INSTANTIATES,

        /** {@code new Runnable() { ... }} → caller –INSTANTIATES_ANONYMOUS→ anon-type */
        INSTANTIATES_ANONYMOUS,

        /** {@code Foo::bar}, {@code Foo::new} → caller –REFERENCES_METHOD→ target */
        REFERENCES_METHOD,

        // ── Field access ──────────────────────────────────────────────────────

        /** {@code this.value}, {@code obj.field} */
        READS_FIELD,

        /** {@code this.value = x} */
        WRITES_FIELD,

        // ── Local variable access ─────────────────────────────────────────────

        /** Caller reads a local variable or method parameter. */
        READS_LOCAL_VAR,

        /** Caller writes a local variable or method parameter. */
        WRITES_LOCAL_VAR,

        // ── Exceptions ───────────────────────────────────────────────────────

        /** Method/constructor contains {@code throw new FooException(...)}. */
        THROWS,

        // ── Annotations ──────────────────────────────────────────────────────

        /** CLASS/METHOD/FIELD → ANNOTATION type. */
        ANNOTATED_WITH,

        // ── Type references ───────────────────────────────────────────────────

        /** {@code Foo.class}, {@code instanceof Foo}, cast to {@code Foo}. */
        REFERENCES_TYPE,

        // ── Inheritance ───────────────────────────────────────────────────────

        /** child –OVERRIDES→ parent method */
        OVERRIDES,

        // ── Generics ─────────────────────────────────────────────────────────

        /**
         * A type or executable has a generic type parameter.
         * {@code class Foo<T>} → Foo –HAS_TYPE_PARAM→ T
         * {@code <R> R map(...)} → map –HAS_TYPE_PARAM→ R
         */
        HAS_TYPE_PARAM,

        /**
         * A type parameter has an upper bound.
         * {@code T extends Comparable<T>} → T –HAS_BOUND→ Comparable
         */
        HAS_BOUND,

        // ── Annotation attributes ─────────────────────────────────────────────

        /**
         * An annotation type declares an attribute element.
         * {@code @interface Foo { String value(); }} → Foo –ANNOTATION_ATTR→ value
         */
        ANNOTATION_ATTR
    }

    /**
     * A node in the symbol graph.
     *
     * @param id            unique node identifier
     * @param kind          {@link NodeKind} of this node
     * @param simpleName    unqualified name
     * @param filePath      source-relative file path
     * @param startLine     first source line (1-based)
     * @param endLine       last source line (1-based)
     * @param sourceSnippet pretty-printed source text produced by Spoon's
     *                      {@code toString()}; empty for external/synthetic nodes
     */
    public record GraphNode(
            String id,
            NodeKind kind,
            String simpleName,
            String filePath,
            int startLine,
            int endLine,
            String sourceSnippet
    ) {}

    /** A directed, typed edge between two graph nodes. */
    public record GraphEdge(
            String fromId,
            String toId,
            EdgeKind kind,
            String filePath,
            int line
    ) {}

    /** The full symbol graph of a project. */
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
            edgesFrom.computeIfAbsent(e.fromId(), k -> new ArrayList<>()).add(e);
            edgesTo.computeIfAbsent(e.toId(), k -> new ArrayList<>()).add(e);
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
    private final Map<Path, ProjectGraph> cache = new ConcurrentHashMap<>();

    public AstGraphService(EdgeKindConfig edgeConfig) {
        this.edgeConfig = edgeConfig;
    }

    public ProjectGraph buildGraph(Path projectRoot) {
        return cache.computeIfAbsent(projectRoot, this::doBuildGraph);
    }

    public void invalidate(Path projectRoot) {
        cache.remove(projectRoot);
    }

    // ------------------------------------------------------------------
    // Path normalisation
    // ------------------------------------------------------------------

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

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    private List<String> collectSourceRoots(Path projectRoot) throws IOException {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        );
        List<String> roots = candidates.stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
        if (!roots.isEmpty()) return roots;

        List<String> fallback = new ArrayList<>();
        try (Stream<Path> top = Files.list(projectRoot)) {
            top.filter(Files::isDirectory)
               .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
               .filter(p -> !p.getFileName().toString().startsWith("."))
               .forEach(p -> fallback.add(p.toString()));
        }
        return fallback.isEmpty() ? List.of(projectRoot.toString()) : fallback;
    }

    @SuppressWarnings("unchecked")
    private ProjectGraph doBuildGraph(Path projectRoot) {
        log.info("Building Spoon AST graph for {}", projectRoot);
        try {
            List<String> sourceRoots = collectSourceRoots(projectRoot);
            log.info("Source roots for Spoon: {}", sourceRoots);

            Launcher launcher = new Launcher();
            sourceRoots.forEach(launcher::addInputResource);
            launcher.getEnvironment().setNoClasspath(true);
            launcher.getEnvironment().setCommentEnabled(true);
            launcher.getEnvironment().setAutoImports(false);
            try { launcher.getEnvironment().setIgnoreDuplicateDeclarations(true); }
            catch (NoSuchMethodError ignored) {}

            CtModel model;
            try {
                model = launcher.buildModel();
            } catch (ModelBuildingException mbe) {
                log.warn("Spoon ModelBuildingException — using partial model: {}", mbe.getMessage());
                model = launcher.getModel();
                if (model == null) return new ProjectGraph();
            }

            var graph = new ProjectGraph();
            String root = projectRoot.toString();

            // ── 1. Type nodes ────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type);
                if (id == null) return;
                String file = relPath(root, sourceFile(type));
                int[] ln = lines(type);
                graph.addNode(new GraphNode(id, NodeKind.CLASS, type.getSimpleName(),
                        file, ln[0], ln[1], snippet(type)));

                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    type.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(id,
                                    ann.getAnnotationType().getQualifiedName(),
                                    EdgeKind.ANNOTATED_WITH, file, ln[0])));
                }
            });

            // ── 2. EXTENDS / IMPLEMENTS ──────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                    String id = qualifiedName(type);
                    if (id == null) return;
                    String file = relPath(root, sourceFile(type));
                    int[] ln = lines(type);

                    if (edgeConfig.isEnabled(EdgeKind.EXTENDS) && type instanceof CtClass<?> cls) {
                        var superRef = cls.getSuperclass();
                        if (superRef != null)
                            graph.addEdge(new GraphEdge(id, superRef.getQualifiedName(),
                                    EdgeKind.EXTENDS, file, ln[0]));
                    }
                    if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                        type.getSuperInterfaces().forEach(ifRef ->
                                graph.addEdge(new GraphEdge(id, ifRef.getQualifiedName(),
                                        EdgeKind.IMPLEMENTS, file, ln[0])));
                    }
                });
            }

            // ── 3. TYPE_PARAM nodes (classes) ────────────────────────────────
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
                                file, tpLn[0], tpLn[1], snippet(tp)));
                        graph.addEdge(new GraphEdge(ownerId, tpId, EdgeKind.HAS_TYPE_PARAM,
                                file, ownerLn[0]));

                        if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                            tp.getSuperclass();
                            tp.getBounds().forEach(bound ->
                                    graph.addEdge(new GraphEdge(tpId,
                                            bound.getQualifiedName(),
                                            EdgeKind.HAS_BOUND, file, tpLn[0])));
                        }
                    });
                });
            }

            // ── 4. Method nodes ──────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
                String id = typeMemberExecId(m);
                if (id == null) return;
                String file = relPath(root, sourceFile(m));
                int[] ln = lines(m);
                graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                        file, ln[0], ln[1], snippet(m)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(m.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.DECLARES, file, ln[0]));
                }
                if (edgeConfig.isEnabled(EdgeKind.OVERRIDES)) {
                    m.getTopDefinitions().stream().findFirst()
                            .filter(top -> top != m)
                            .map(this::typeMemberExecId)
                            .ifPresent(superId ->
                                    graph.addEdge(new GraphEdge(id, superId,
                                            EdgeKind.OVERRIDES, file, ln[0])));
                }
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    m.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(id,
                                    ann.getAnnotationType().getQualifiedName(),
                                    EdgeKind.ANNOTATED_WITH, file, ln[0])));
                }
            });

            // ── 5. Constructor nodes ─────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
                String id = typeMemberExecId(c);
                if (id == null) return;
                String file = relPath(root, sourceFile(c));
                int[] ln = lines(c);
                graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                        file, ln[0], ln[1], snippet(c)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(c.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.DECLARES, file, ln[0]));
                }
            });

            // ── 6. Field nodes ───────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
                // Skip annotation attribute methods — handled in step 8
                if (field.getDeclaringType() instanceof CtAnnotationType) return;
                String id = fieldId(field);
                if (id == null) return;
                String file = relPath(root, sourceFile(field));
                int[] ln = lines(field);
                graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                        file, ln[0], ln[1], snippet(field)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(field.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.DECLARES, file, ln[0]));
                }
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    field.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(id,
                                    ann.getAnnotationType().getQualifiedName(),
                                    EdgeKind.ANNOTATED_WITH, file, ln[0])));
                }
            });

            // ── 7. Local variable + parameter nodes ──────────────────────────
            model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
                if (v instanceof CtField) return;
                String id = varId(v);
                if (id == null) return;
                String file = relPath(root, sourceFile(v));
                int[] ln = lines(v);
                graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                        file, ln[0], ln[1], snippet(v)));
            });

            // ── 8. ANNOTATION_ATTRIBUTE nodes ────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
                model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                    String annId = qualifiedName(ann);
                    if (annId == null) return;
                    String file = relPath(root, sourceFile(ann));

                    ann.getMethods().forEach(m -> {
                        String attrId = annId + "#" + m.getSimpleName();
                        int[] ln = lines(m);
                        graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                                m.getSimpleName(), file, ln[0], ln[1], snippet(m)));
                        graph.addEdge(new GraphEdge(annId, attrId,
                                EdgeKind.ANNOTATION_ATTR, file, ln[0]));
                    });
                });
            }

            // ── 9. Lambda nodes ──────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
                String file = relPath(root, sourceFile(lambda));
                int[] ln = lines(lambda);
                String id = "lambda@" + file + ":" + ln[0];
                graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "λ",
                        file, ln[0], ln[1], snippet(lambda)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    CtMethod<?> em = lambda.getParent(CtMethod.class);
                    if (em != null) {
                        String encId = typeMemberExecId(em);
                        if (encId != null)
                            graph.addEdge(new GraphEdge(encId, id, EdgeKind.DECLARES, file, ln[0]));
                    } else {
                        CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                        if (ec != null) {
                            String encId = typeMemberExecId(ec);
                            if (encId != null)
                                graph.addEdge(new GraphEdge(encId, id, EdgeKind.DECLARES, file, ln[0]));
                        }
                    }
                }
            });

            // ── 10. INVOKES ──────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                    String callerId = nearestExecId(inv);
                    if (callerId == null) return;
                    graph.addEdge(new GraphEdge(callerId, execRefId(inv.getExecutable()),
                            EdgeKind.INVOKES, relPath(root, sourceFile(inv)), posLine(inv)));
                });
            }

            // ── 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS)) {
                model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                    String callerId = nearestExecId(cc);
                    if (callerId == null) return;
                    String typeId = cc.getExecutable().getDeclaringType() != null
                            ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                    boolean isAnon = cc instanceof CtNewClass;
                    EdgeKind kind = isAnon ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(callerId, typeId, kind,
                                relPath(root, sourceFile(cc)), posLine(cc)));
                });
            }

            // ── 12. REFERENCES_METHOD ────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD)) {
                model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                    String callerId = nearestExecId(ref);
                    if (callerId == null) return;
                    graph.addEdge(new GraphEdge(callerId, execRefId(ref.getExecutable()),
                            EdgeKind.REFERENCES_METHOD,
                            relPath(root, sourceFile(ref)), posLine(ref)));
                });
            }

            // ── 13. READS_FIELD / WRITES_FIELD ───────────────────────────────
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
                        graph.addEdge(new GraphEdge(execId, fId, kind,
                                relPath(root, sourceFile(fa)), posLine(fa)));
                });
            }

            // ── 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ───────────────────────
            if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR)) {
                model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                    if (va instanceof CtFieldAccess) return;
                    String execId = nearestExecId(va);
                    if (execId == null) return;
                    String vId = varRefId(va.getVariable());
                    EdgeKind kind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(execId, vId, kind,
                                relPath(root, sourceFile(va)), posLine(va)));
                });
            }

            // ── 15. THROWS ───────────────────────────────────────────────────
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
                    graph.addEdge(new GraphEdge(execId, typeId, EdgeKind.THROWS,
                            relPath(root, sourceFile(thr)), posLine(thr)));
                });
            }

            // ── 16. REFERENCES_TYPE ──────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE)) {
                model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                    String execId = nearestExecId(ta);
                    if (execId == null) return;
                    if (ta.getAccessedType() == null) return;
                    graph.addEdge(new GraphEdge(execId,
                            ta.getAccessedType().getQualifiedName(),
                            EdgeKind.REFERENCES_TYPE,
                            relPath(root, sourceFile(ta)), posLine(ta)));
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
    // Source snippet helper
    // ------------------------------------------------------------------

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

    /**
     * Stable id for a {@link CtTypeParameter}: {@code ownerId#T}
     * where {@code ownerId} is the qualified name of the declaring type or
     * the signature of the declaring executable.
     */
    private String typeParamId(String ownerId, CtTypeParameter tp) {
        return ownerId + "#<" + tp.getSimpleName() + ">";
    }

    /**
     * Returns a stable id for any {@link CtFormalTypeDeclarer} —
     * either a qualified class name or an executable id.
     */
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

    private int[] lines(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition())
                return new int[]{ pos.getLine(), pos.getEndLine() };
        } catch (Exception ignored) {}
        return new int[]{ 0, 0 };
    }

    private int posLine(CtElement el) {
        try {
            var pos = el.getPosition();
            return pos.isValidPosition() ? pos.getLine() : 0;
        } catch (Exception e) { return 0; }
    }
}
