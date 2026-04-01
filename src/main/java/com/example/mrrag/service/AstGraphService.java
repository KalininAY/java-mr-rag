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
 * Nodes: CLASS, METHOD, FIELD, VARIABLE, LAMBDA, ANNOTATION<br>
 * Edges: INVOKES, READS_FIELD, WRITES_FIELD, READS_VAR, WRITES_VAR,
 *        CONTAINS, OVERRIDES, ANNOTATED_WITH
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

    public record GraphNode(
            String id,
            NodeKind kind,
            String simpleName,
            String filePath,
            int startLine,
            int endLine
    ) {}

    public record GraphEdge(
            String fromId,
            String toId,
            EdgeKind kind,
            String filePath,
            int line
    ) {}

    public static class ProjectGraph {
        public final Map<String, GraphNode>       nodes        = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesFrom    = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesTo      = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> bySimpleName = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byLine       = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byFile       = new LinkedHashMap<>();

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

        /** All nodes whose line-range covers the given line in a file. */
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
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        // Do NOT call setComplianceLevel() — Spoon 10.x uses JDT internally
        // and passes the level as "-<N>" which JDT only understands up to 17.
        // Without an explicit level Spoon picks a safe default automatically.

        var model = launcher.buildModel();
        var graph = new ProjectGraph();
        String root = projectRoot.toString();

        // ---- 1. Type nodes -----------------------------------------------------
        model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
            String id = qualifiedName(type);
            if (id == null) return;
            String file = relPath(root, sourceFile(type));
            int[] ln = lines(type);
            graph.addNode(new GraphNode(id, NodeKind.CLASS, type.getSimpleName(), file, ln[0], ln[1]));
        });

        // ---- 2. Method nodes ---------------------------------------------------
        model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
            String id = typeMemberExecId(m);
            if (id == null) return;
            String file = relPath(root, sourceFile(m));
            int[] ln = lines(m);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(), file, ln[0], ln[1]));

            String classId = qualifiedName(m.getDeclaringType());
            if (classId != null)
                graph.addEdge(new GraphEdge(classId, id, EdgeKind.CONTAINS, file, ln[0]));

            CtMethod<?> top = m.getTopDefinitions().stream().findFirst().orElse(null);
            if (top != null && top != m) {
                String superId = typeMemberExecId(top);
                if (superId != null)
                    graph.addEdge(new GraphEdge(id, superId, EdgeKind.OVERRIDES, file, ln[0]));
            }

            m.getAnnotations().forEach(ann ->
                    graph.addEdge(new GraphEdge(id,
                            ann.getAnnotationType().getQualifiedName(),
                            EdgeKind.ANNOTATED_WITH, file, ln[0])));
        });

        // ---- 3. Constructor nodes ----------------------------------------------
        model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
            String id = typeMemberExecId(c);
            if (id == null) return;
            String file = relPath(root, sourceFile(c));
            int[] ln = lines(c);
            graph.addNode(new GraphNode(id, NodeKind.METHOD, c.getSimpleName(), file, ln[0], ln[1]));

            String classId = qualifiedName(c.getDeclaringType());
            if (classId != null)
                graph.addEdge(new GraphEdge(classId, id, EdgeKind.CONTAINS, file, ln[0]));
        });

        // ---- 4. Field nodes ----------------------------------------------------
        model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
            String id = fieldId(field);
            if (id == null) return;
            String file = relPath(root, sourceFile(field));
            int[] ln = lines(field);
            graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(), file, ln[0], ln[1]));

            String classId = qualifiedName(field.getDeclaringType());
            if (classId != null)
                graph.addEdge(new GraphEdge(classId, id, EdgeKind.CONTAINS, file, ln[0]));
        });

        // ---- 5. Local variable + parameter nodes --------------------------------
        model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
            if (v instanceof CtField) return;
            String id = varId(v);
            if (id == null) return;
            String file = relPath(root, sourceFile(v));
            int[] ln = lines(v);
            graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(), file, ln[0], ln[1]));
        });

        // ---- 6. Lambda nodes ---------------------------------------------------
        model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
            String file = relPath(root, sourceFile(lambda));
            int[] ln = lines(lambda);
            String id = "lambda@" + file + ":" + ln[0];
            graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb", file, ln[0], ln[1]));

            CtMethod<?> em = lambda.getParent(CtMethod.class);
            if (em != null) {
                String encId = typeMemberExecId(em);
                if (encId != null)
                    graph.addEdge(new GraphEdge(encId, id, EdgeKind.CONTAINS, file, ln[0]));
            } else {
                CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                if (ec != null) {
                    String encId = typeMemberExecId(ec);
                    if (encId != null)
                        graph.addEdge(new GraphEdge(encId, id, EdgeKind.CONTAINS, file, ln[0]));
                }
            }
        });

        // ---- 7. INVOKES edges --------------------------------------------------
        model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
            String callerId = nearestExecId(inv);
            if (callerId == null) return;
            String calleeId = execRefId(inv.getExecutable());
            graph.addEdge(new GraphEdge(callerId, calleeId, EdgeKind.INVOKES,
                    relPath(root, sourceFile(inv)), posLine(inv)));
        });

        // ---- 8. Field read / write edges ---------------------------------------
        model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
            String execId = nearestExecId(fa);
            if (execId == null) return;
            CtFieldReference<?> ref = fa.getVariable();
            String fId = ref.getDeclaringType() != null
                    ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                    : "?." + ref.getSimpleName();
            EdgeKind kind = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
            graph.addEdge(new GraphEdge(execId, fId, kind,
                    relPath(root, sourceFile(fa)), posLine(fa)));
        });

        // ---- 9. Variable read / write edges ------------------------------------
        model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
            if (va instanceof CtFieldAccess) return;
            String execId = nearestExecId(va);
            if (execId == null) return;
            String vId = varRefId(va.getVariable());
            EdgeKind kind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_VAR : EdgeKind.READS_VAR;
            graph.addEdge(new GraphEdge(execId, vId, kind,
                    relPath(root, sourceFile(va)), posLine(va)));
        });

        log.info("Spoon graph built: {} nodes, {} edge-sources",
                graph.nodes.size(), graph.edgesFrom.size());
        return graph;
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
