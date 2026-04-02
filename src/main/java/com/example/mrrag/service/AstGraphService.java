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
 * <p><b>Nodes:</b> CLASS, CONSTRUCTOR, METHOD, FIELD, VARIABLE, LAMBDA, ANNOTATION<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS,
 * REFERENCES_METHOD, READS_FIELD, WRITES_FIELD, READS_LOCAL_VAR, WRITES_LOCAL_VAR,
 * THROWS, ANNOTATED_WITH, REFERENCES_TYPE, OVERRIDES
 *
 * <p>Each edge type can be individually enabled/disabled via
 * {@code graph.edge.<EDGE_KIND>.enabled} in {@code application.yml}.
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
        /** A local variable or method parameter. */
        VARIABLE,
        /** A lambda expression. */
        LAMBDA,
        /** An annotation type (target of {@code ANNOTATED_WITH} edges). */
        ANNOTATION
    }

    /**
     * Kinds of directed graph edges.
     * Each value can be toggled via {@code graph.edge.<name>.enabled=true/false}.
     */
    public enum EdgeKind {

        // ── Structural (declaration) ────────────────────────────────────────

        /**
         * Owner declares a child member.
         * Examples: CLASS→METHOD, CLASS→FIELD, CLASS→CONSTRUCTOR, METHOD→LAMBDA.
         */
        DECLARES,

        // ── Type hierarchy ──────────────────────────────────────────────────

        /**
         * A class extends another class.
         * {@code class A extends B} → A –EXTENDS→ B
         */
        EXTENDS,

        /**
         * A class or abstract type implements an interface.
         * {@code class A implements I} → A –IMPLEMENTS→ I
         */
        IMPLEMENTS,

        // ── Invocations ─────────────────────────────────────────────────────

        /**
         * Caller method/constructor invokes a callee method.
         * {@code foo.bar()} → caller –INVOKES→ callee
         */
        INVOKES,

        /**
         * Caller instantiates a class via its constructor.
         * {@code new Foo(arg)} → caller –INSTANTIATES→ Foo
         */
        INSTANTIATES,

        /**
         * Caller creates an anonymous class.
         * {@code new Runnable() \{ ... \}} → caller –INSTANTIATES_ANONYMOUS→ anonymous-type
         */
        INSTANTIATES_ANONYMOUS,

        /**
         * Method reference to a method or constructor.
         * {@code Foo::bar}, {@code Foo::new} → caller –REFERENCES_METHOD→ target
         */
        REFERENCES_METHOD,

        // ── Field access ────────────────────────────────────────────────────

        /**
         * Caller reads a class field.
         * {@code this.value}, {@code obj.field}
         */
        READS_FIELD,

        /**
         * Caller writes a class field.
         * {@code this.value = x}
         */
        WRITES_FIELD,

        // ── Local variable access ───────────────────────────────────────────

        /**
         * Caller reads a local variable or method parameter.
         */
        READS_LOCAL_VAR,

        /**
         * Caller writes a local variable or method parameter.
         */
        WRITES_LOCAL_VAR,

        // ── Exceptions ──────────────────────────────────────────────────────

        /**
         * A method/constructor contains a {@code throw new FooException(...)} statement.
         * caller –THROWS→ ExceptionType
         */
        THROWS,

        // ── Annotations ─────────────────────────────────────────────────────

        /**
         * A node is annotated with an annotation type.
         * CLASS/METHOD/FIELD → ANNOTATION
         */
        ANNOTATED_WITH,

        // ── Type references ─────────────────────────────────────────────────

        /**
         * An access to a type as a value:
         * {@code Foo.class}, {@code instanceof Foo}, cast to {@code Foo}.
         */
        REFERENCES_TYPE,

        // ── Inheritance ─────────────────────────────────────────────────────

        /**
         * A method overrides a method in a supertype.
         * child –OVERRIDES→ parent
         */
        OVERRIDES
    }

    /** A node in the symbol graph. */
    public record GraphNode(
            String id,
            NodeKind kind,
            String simpleName,
            String filePath,
            int startLine,
            int endLine
    ) {}

    /** A directed, typed edge between two graph nodes. */
    public record GraphEdge(
            /** ID of the source node (caller / owner / subtype). */
            String fromId,
            /** ID of the target node (callee / member / supertype). */
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

        /**
         * All file paths stored in the graph (relative to the project root).
         * Used by {@link AstGraphService#normalizeFilePath} for suffix matching.
         */
        public Set<String> allFilePaths() {
            return byFile.keySet();
        }

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

        /** All outgoing edges from a node. */
        public List<GraphEdge> outgoing(String nodeId) {
            return edgesFrom.getOrDefault(nodeId, List.of());
        }

        /** All incoming edges to a node. */
        public List<GraphEdge> incoming(String nodeId) {
            return edgesTo.getOrDefault(nodeId, List.of());
        }

        /** All outgoing edges of a specific kind from a node. */
        public List<GraphEdge> outgoing(String nodeId, EdgeKind kind) {
            return outgoing(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }

        /** All incoming edges of a specific kind to a node. */
        public List<GraphEdge> incoming(String nodeId, EdgeKind kind) {
            return incoming(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }

        /** All nodes whose line-range covers the given line in a file. */
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
     * @param diffPath path as reported by GitLab diff
     * @param graph    the project graph to look up known paths in
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

    /** Directory segments that should never be fed to Spoon as source roots. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    /**
     * Collects only real source directories ({@code src/main/java}, {@code src/test/java},
     * or the project root itself), skipping build/target output directories.
     */
    private List<String> collectSourceRoots(Path projectRoot) throws IOException {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        );
        List<String> roots = candidates.stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
        if (!roots.isEmpty()) {
            log.debug("Using standard source roots: {}", roots);
            return roots;
        }
        List<String> fallback = new ArrayList<>();
        try (Stream<Path> top = Files.list(projectRoot)) {
            top.filter(Files::isDirectory)
               .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
               .filter(p -> !p.getFileName().toString().startsWith("."))
               .forEach(p -> fallback.add(p.toString()));
        }
        if (!fallback.isEmpty()) {
            log.debug("Fallback source roots: {}", fallback);
            return fallback;
        }
        return List.of(projectRoot.toString());
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
                        projectRoot, mbe.getMessage());
                model = launcher.getModel();
                if (model == null) {
                    log.error("Spoon returned null model for {}, returning empty graph", projectRoot);
                    return new ProjectGraph();
                }
            }

            var graph = new ProjectGraph();
            String root = projectRoot.toString();

            // ── 1. Type nodes ────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type);
                if (id == null) return;
                String file = relPath(root, sourceFile(type));
                int[] ln = lines(type);
                graph.addNode(new GraphNode(id, NodeKind.CLASS, type.getSimpleName(), file, ln[0], ln[1]));
            });

            // ── 2. EXTENDS / IMPLEMENTS edges ────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                    String id = qualifiedName(type);
                    if (id == null) return;
                    String file = relPath(root, sourceFile(type));
                    int[] ln = lines(type);

                    if (edgeConfig.isEnabled(EdgeKind.EXTENDS) && type instanceof CtClass<?> cls) {
                        var superRef = cls.getSuperclass();
                        if (superRef != null) {
                            String superId = superRef.getQualifiedName();
                            graph.addEdge(new GraphEdge(id, superId, EdgeKind.EXTENDS, file, ln[0]));
                        }
                    }

                    if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                        type.getSuperInterfaces().forEach(ifRef ->
                                graph.addEdge(new GraphEdge(id, ifRef.getQualifiedName(),
                                        EdgeKind.IMPLEMENTS, file, ln[0])));
                    }
                });
            }

            // ── 3. Method nodes ──────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
                String id = typeMemberExecId(m);
                if (id == null) return;
                String file = relPath(root, sourceFile(m));
                int[] ln = lines(m);
                graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(), file, ln[0], ln[1]));

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
                                    graph.addEdge(new GraphEdge(id, superId, EdgeKind.OVERRIDES, file, ln[0])));
                }

                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    m.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(id,
                                    ann.getAnnotationType().getQualifiedName(),
                                    EdgeKind.ANNOTATED_WITH, file, ln[0])));
                }
            });

            // ── 4. Constructor nodes ─────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
                String id = typeMemberExecId(c);
                if (id == null) return;
                String file = relPath(root, sourceFile(c));
                int[] ln = lines(c);
                graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(), file, ln[0], ln[1]));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(c.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.DECLARES, file, ln[0]));
                }
            });

            // ── 5. Field nodes ───────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
                String id = fieldId(field);
                if (id == null) return;
                String file = relPath(root, sourceFile(field));
                int[] ln = lines(field);
                graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(), file, ln[0], ln[1]));

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

            // ── 6. Local variable + parameter nodes ──────────────────────────
            model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
                if (v instanceof CtField) return;
                String id = varId(v);
                if (id == null) return;
                String file = relPath(root, sourceFile(v));
                int[] ln = lines(v);
                graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(), file, ln[0], ln[1]));
            });

            // ── 7. Lambda nodes ──────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
                String file = relPath(root, sourceFile(lambda));
                int[] ln = lines(lambda);
                String id = "lambda@" + file + ":" + ln[0];
                graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb", file, ln[0], ln[1]));

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

            // ── 8. INVOKES edges ─────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                    String callerId = nearestExecId(inv);
                    if (callerId == null) return;
                    String calleeId = execRefId(inv.getExecutable());
                    graph.addEdge(new GraphEdge(callerId, calleeId, EdgeKind.INVOKES,
                            relPath(root, sourceFile(inv)), posLine(inv)));
                });
            }

            // ── 9. INSTANTIATES / INSTANTIATES_ANONYMOUS edges ───────────────
            if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS)) {
                model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                    String callerId = nearestExecId(cc);
                    if (callerId == null) return;
                    String typeId = cc.getExecutable().getDeclaringType() != null
                            ? cc.getExecutable().getDeclaringType().getQualifiedName()
                            : "?";

                    boolean isAnon = cc instanceof CtNewClass;
                    EdgeKind kind = isAnon ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                    if (edgeConfig.isEnabled(kind)) {
                        graph.addEdge(new GraphEdge(callerId, typeId, kind,
                                relPath(root, sourceFile(cc)), posLine(cc)));
                    }
                });
            }

            // ── 10. REFERENCES_METHOD edges (method references Foo::bar) ─────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD)) {
                model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                    String callerId = nearestExecId(ref);
                    if (callerId == null) return;
                    String targetId = execRefId(ref.getExecutable());
                    graph.addEdge(new GraphEdge(callerId, targetId, EdgeKind.REFERENCES_METHOD,
                            relPath(root, sourceFile(ref)), posLine(ref)));
                });
            }

            // ── 11. READS_FIELD / WRITES_FIELD edges ─────────────────────────
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

            // ── 12. READS_LOCAL_VAR / WRITES_LOCAL_VAR edges ─────────────────
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

            // ── 13. THROWS edges ─────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.THROWS)) {
                model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                    String execId = nearestExecId(thr);
                    if (execId == null) return;
                    CtExpression<?> thrown = thr.getThrownExpression();
                    String typeId = "?";
                    if (thrown instanceof CtConstructorCall<?> cc && cc.getExecutable().getDeclaringType() != null) {
                        typeId = cc.getExecutable().getDeclaringType().getQualifiedName();
                    }
                    graph.addEdge(new GraphEdge(execId, typeId, EdgeKind.THROWS,
                            relPath(root, sourceFile(thr)), posLine(thr)));
                });
            }

            // ── 14. REFERENCES_TYPE edges (Foo.class, instanceof, cast) ──────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE)) {
                model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                    String execId = nearestExecId(ta);
                    if (execId == null) return;
                    if (ta.getAccessedType() == null) return;
                    String typeId = ta.getAccessedType().getQualifiedName();
                    graph.addEdge(new GraphEdge(execId, typeId, EdgeKind.REFERENCES_TYPE,
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
        } catch (Exception e) {
            return 0;
        }
    }
}
