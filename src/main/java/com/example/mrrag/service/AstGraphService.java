package com.example.mrrag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 * <p>
 * Nodes:
 * <ul>
 *   <li>{@link NodeKind#CLASS} — classes, interfaces, enums, records, annotations</li>
 *   <li>{@link NodeKind#METHOD} — methods and constructors</li>
 *   <li>{@link NodeKind#FIELD} — fields</li>
 *   <li>{@link NodeKind#VARIABLE} — local variables and parameters</li>
 *   <li>{@link NodeKind#LAMBDA} — lambda expressions</li>
 *   <li>{@link NodeKind#ANNOTATION} — annotation usages</li>
 * </ul>
 * Edges:
 * <ul>
 *   <li>{@link EdgeKind#INVOKES} — method call (caller → callee)</li>
 *   <li>{@link EdgeKind#READS_FIELD} — field read (method → field)</li>
 *   <li>{@link EdgeKind#WRITES_FIELD} — field write (method → field)</li>
 *   <li>{@link EdgeKind#READS_VAR} — variable read inside method</li>
 *   <li>{@link EdgeKind#WRITES_VAR} — variable write inside method</li>
 *   <li>{@link EdgeKind#CONTAINS} — structural parent/child (class→method, method→lambda, etc.)</li>
 *   <li>{@link EdgeKind#OVERRIDES} — method override relationship</li>
 *   <li>{@link EdgeKind#ANNOTATED_WITH} — declaration annotated with type</li>
 * </ul>
 */
@Slf4j
@Service
public class AstGraphService {

    // ------------------------------------------------------------------
    // Public model
    // ------------------------------------------------------------------

    public enum NodeKind { CLASS, METHOD, FIELD, VARIABLE, LAMBDA, ANNOTATION }

    public enum EdgeKind {
        INVOKES, READS_FIELD, WRITES_FIELD, READS_VAR, WRITES_VAR,
        CONTAINS, OVERRIDES, ANNOTATED_WITH
    }

    /** A single node in the graph representing a named symbol. */
    public record GraphNode(
            String id,          // unique key, e.g. "com.example.Foo#bar(int)"
            NodeKind kind,
            String simpleName,
            String filePath,    // relative to project root
            int startLine,
            int endLine         // same as startLine for single-line symbols
    ) {}

    /** A directed edge between two graph nodes. */
    public record GraphEdge(
            String fromId,
            String toId,
            EdgeKind kind,
            String filePath,    // file where the relationship manifests
            int line            // line where the relationship manifests
    ) {}

    /** The complete graph for one project checkout. */
    public static class ProjectGraph {
        /** id → node */
        public final Map<String, GraphNode>        nodes         = new LinkedHashMap<>();
        /** fromId → list of outgoing edges */
        public final Map<String, List<GraphEdge>>  edgesFrom     = new LinkedHashMap<>();
        /** toId   → list of incoming edges */
        public final Map<String, List<GraphEdge>>  edgesTo       = new LinkedHashMap<>();
        /** simpleName → all nodes sharing that name */
        public final Map<String, List<GraphNode>>  bySimpleName  = new LinkedHashMap<>();
        /** "relPath#line" → nodes that start on that line */
        public final Map<String, List<GraphNode>>  byLine        = new LinkedHashMap<>();
        /** "relPath" → all nodes declared in that file */
        public final Map<String, List<GraphNode>>  byFile        = new LinkedHashMap<>();

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

        /** All nodes in a file whose range covers the given line. */
        public List<GraphNode> nodesAtLine(String relPath, int line) {
            return byFile.getOrDefault(relPath, List.of()).stream()
                    .filter(n -> n.startLine() <= line && n.endLine() >= line)
                    .toList();
        }
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private final Map<Path, ProjectGraph> cache = new ConcurrentHashMap<>();

    public ProjectGraph buildGraph(Path projectRoot) {
        return cache.computeIfAbsent(projectRoot, this::doBuildGraph);
    }

    public void invalidate(Path projectRoot) {
        cache.remove(projectRoot);
    }

    // ------------------------------------------------------------------
    // Graph construction
    // ------------------------------------------------------------------

    private ProjectGraph doBuildGraph(Path projectRoot) {
        log.info("Building Spoon AST graph for {}", projectRoot);
        Launcher launcher = new Launcher();
        launcher.addInputResource(projectRoot.toString());
        launcher.getEnvironment().setNoClasspath(true);      // no deps needed
        launcher.getEnvironment().setCommentEnabled(false);  // faster
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setComplianceLevel(21);
        // silence Spoon's own logger noise
        launcher.getEnvironment().setLevel("OFF");

        var model = launcher.buildModel();
        var graph = new ProjectGraph();
        String root = projectRoot.toString();

        // ---- 1. Declare all type nodes ---------------------------------
        model.getElements(new TypeFilter<>(CtType.class)).forEach(type ->
                typeNodeId(type).ifPresent(id -> {
                    String file = relPath(root, sourceFile(type));
                    int[] lines = lines(type);
                    graph.addNode(new GraphNode(id, NodeKind.CLASS,
                            type.getSimpleName(), file, lines[0], lines[1]));
                })
        );

        // ---- 2. Declare method / constructor nodes ----------------------
        model.getElements(new TypeFilter<>(CtExecutable.class)).forEach(exec -> {
            String id = execId(exec);
            if (id == null) return;
            String file = relPath(root, sourceFile(exec));
            int[] lines = lines(exec);
            graph.addNode(new GraphNode(id, NodeKind.METHOD,
                    exec.getSimpleName(), file, lines[0], lines[1]));

            // CONTAINS: class → method
            if (exec.getDeclaringType() != null) {
                typeNodeId(exec.getDeclaringType()).ifPresent(classId ->
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.CONTAINS, file, lines[0])));
            }

            // OVERRIDES
            if (exec instanceof CtMethod<?> m) {
                CtMethod<?> top = m.getTopDefinitions().stream().findFirst().orElse(null);
                if (top != null && top != m) {
                    String superId = execId(top);
                    if (superId != null)
                        graph.addEdge(new GraphEdge(id, superId, EdgeKind.OVERRIDES, file, lines[0]));
                }
            }

            // ANNOTATED_WITH
            exec.getAnnotations().forEach(ann ->
                    graph.addEdge(new GraphEdge(id,
                            ann.getAnnotationType().getQualifiedName(),
                            EdgeKind.ANNOTATED_WITH, file, lines[0])));
        });

        // ---- 3. Field nodes --------------------------------------------
        model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            String id = fieldId(field);
            if (id == null) return;
            String file = relPath(root, sourceFile(field));
            int[] lines = lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD,
                    field.getSimpleName(), file, lines[0], lines[1]));

            // CONTAINS: class → field
            if (field.getDeclaringType() != null) {
                typeNodeId(field.getDeclaringType()).ifPresent(classId ->
                        graph.addEdge(new GraphEdge(classId, id, EdgeKind.CONTAINS, file, lines[0])));
            }
        });

        // ---- 4. Local variable + parameter nodes -----------------------
        model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;  // already handled
            String id = varId(v);
            if (id == null) return;
            String file = relPath(root, sourceFile(v));
            int[] lines = lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE,
                    v.getSimpleName(), file, lines[0], lines[1]));
        });

        // ---- 5. Lambda nodes -------------------------------------------
        model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = relPath(root, sourceFile(lambda));
            int[] lines = lines(lambda);
            String id = "lambda@" + file + ":" + lines[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "λ", file, lines[0], lines[1]));

            // CONTAINS: enclosing method → lambda
            CtExecutable<?> enclosing = lambda.getParent(CtExecutable.class);
            if (enclosing != null) {
                String encId = execId(enclosing);
                if (encId != null)
                    graph.addEdge(new GraphEdge(encId, id, EdgeKind.CONTAINS, file, lines[0]));
            }
        });

        // ---- 6. Invocation edges (INVOKES) -----------------------------
        model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
            CtExecutable<?> caller = inv.getParent(CtExecutable.class);
            if (caller == null) return;
            String callerId = execId(caller);
            if (callerId == null) return;

            CtExecutableReference<?> ref = inv.getExecutable();
            String calleeId = execRefId(ref);
            String file = relPath(root, sourceFile(inv));
            int line = inv.getPosition().isValidPosition() ? inv.getPosition().getLine() : 0;

            graph.addEdge(new GraphEdge(callerId, calleeId, EdgeKind.INVOKES, file, line));
        });

        // ---- 7. Field read / write edges --------------------------------
        model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
            CtExecutable<?> exec = fa.getParent(CtExecutable.class);
            if (exec == null) return;
            String execNodeId = execId(exec);
            if (execNodeId == null) return;

            CtFieldReference<?> ref = fa.getVariable();
            String fId = ref.getDeclaringType() != null
                    ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                    : "?." + ref.getSimpleName();

            String file = relPath(root, sourceFile(fa));
            int line = fa.getPosition().isValidPosition() ? fa.getPosition().getLine() : 0;
            EdgeKind kind = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
            graph.addEdge(new GraphEdge(execNodeId, fId, kind, file, line));
        });

        // ---- 8. Variable read / write edges ----------------------------
        model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
            if (va instanceof CtFieldAccess) return; // already handled
            CtExecutable<?> exec = va.getParent(CtExecutable.class);
            if (exec == null) return;
            String execNodeId = execId(exec);
            if (execNodeId == null) return;

            String vId = varRefId(va.getVariable());
            String file = relPath(root, sourceFile(va));
            int line = va.getPosition().isValidPosition() ? va.getPosition().getLine() : 0;
            EdgeKind kind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_VAR : EdgeKind.READS_VAR;
            graph.addEdge(new GraphEdge(execNodeId, vId, kind, file, line));
        });

        log.info("Spoon graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
    }

    // ------------------------------------------------------------------
    // ID helpers
    // ------------------------------------------------------------------

    private Optional<String> typeNodeId(CtType<?> type) {
        if (type == null) return Optional.empty();
        String q = type.getQualifiedName();
        return (q == null || q.isBlank()) ? Optional.empty() : Optional.of(q);
    }

    private String execId(CtExecutable<?> exec) {
        if (exec == null) return null;
        try {
            if (exec.getDeclaringType() == null) return exec.getSignature();
            return exec.getDeclaringType().getQualifiedName() + "#" + exec.getSignature();
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
        // key = file:line:name to keep unique across scopes
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
        return abs.startsWith(root) ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    private int[] lines(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition())
                return new int[]{ pos.getLine(), pos.getEndLine() };
        } catch (Exception ignored) {}
        return new int[]{ 0, 0 };
    }
}
