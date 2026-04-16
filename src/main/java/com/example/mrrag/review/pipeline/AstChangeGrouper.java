package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.model.ChangedLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Groups changed lines into {@link ChangeGroup}s exclusively via the AST graph.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Resolve nodes</b> — for each changed line find its AST nodes
 *       (method container, top-level type, exact-line node) by file path and
 *       line number.</li>
 *   <li><b>Build connections</b> — for every ordered pair of distinct anchor
 *       nodes (A, B) collected in Step 1, run BFS from A toward B (up to
 *       {@value #MAX_BFS_DEPTH} hops). If a path exists, emit a
 *       {@link NodeConnection}(A, B, intermediates). Direction matters:
 *       A→B and B→A are separate connections and may produce separate groups.
 *       Intermediate nodes are <em>not</em> re-used as anchors for new pairs.</li>
 *   <li><b>Build groups</b> — each {@link NodeConnection} seeds a
 *       {@link ChangeGroup}: the {@code changedLines} field contains only the
 *       lines whose anchor nodes are A or B; the {@code intermediateNodes}
 *       field carries the BFS intermediate nodes as read-only context.
 *       A line may appear in multiple groups (intentional duplication).
 *       Lines whose nodes matched no connection become singleton groups;
 *       lines with no resolvable AST node are gathered into per-file fallback
 *       groups.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AstChangeGrouper {

    private final GraphQueryService graphQuery;
    private final AtomicInteger groupCounter = new AtomicInteger(0);

    /**
     * Maximum BFS depth when searching for a path between two anchor nodes.
     * A value of 2 covers direct calls (A→B) and one-intermediary paths (A→C→B).
     * Increase to 3 to reach two-hop intermediaries at the cost of broader traversal.
     */
    static final int MAX_BFS_DEPTH = 2;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Groups {@code changedLines} using the AST {@code graph}.
     *
     * @param changedLines set of changed lines from the diff parser
     * @param graph        AST graph of the project; must not be {@code null}
     * @return list of {@link ChangeGroup}s built exclusively from AST relationships
     */
    public List<ChangeGroup> group(Set<ChangedLine> changedLines, ProjectGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null for AstChangeGrouper");

        // Step 1: resolve AST anchor nodes for every non-CONTEXT changed line
        Map<ChangedLine, Set<GraphNode>> lineToNodes = resolveNodes(changedLines, graph);
        log.debug("Step 1 (resolve nodes): {}/{} lines have at least one AST node",
                lineToNodes.values().stream().filter(s -> !s.isEmpty()).count(),
                changedLines.size());

        // Step 2: build directed NodeConnections via BFS
        List<NodeConnection> connections = buildConnections(lineToNodes, graph);
        log.debug("Step 2 (BFS connections): {} connections found", connections.size());

        // Step 3: build ChangeGroups from connections
        List<ChangeGroup> groups = buildGroups(connections, lineToNodes, changedLines);
        log.debug("Step 3 (build groups): {} groups produced", groups.size());

        return groups;
    }

    // -----------------------------------------------------------------------
    // Step 1: resolve AST anchor nodes per changed line
    // -----------------------------------------------------------------------

    /**
     * For each non-CONTEXT changed line, finds all AST nodes whose range
     * covers the line's effective line number:
     * <ul>
     *   <li>the smallest enclosing METHOD node (if any)</li>
     *   <li>the unique top-level CLASS/INTERFACE of the file (if any)</li>
     *   <li>any node whose {@code startLine} equals the line number exactly</li>
     * </ul>
     */
    private Map<ChangedLine, Set<GraphNode>> resolveNodes(
            Set<ChangedLine> lines, ProjectGraph graph) {

        Map<ChangedLine, Set<GraphNode>> result = new LinkedHashMap<>();
        for (ChangedLine line : lines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) continue;
            Set<GraphNode> nodes = new LinkedHashSet<>();
            int ln = line.lineNumber() > 0 ? line.lineNumber() : line.oldLineNumber();
            if (ln > 0) {
                graphQuery.findContainingMethod(graph, line.filePath(), ln)
                        .ifPresent(nodes::add);
                graphQuery.findTopLevelType(graph, line.filePath())
                        .ifPresent(nodes::add);
                // exact-line node (field declaration, annotation, etc.)
                graph.nodes.values().stream()
                        .filter(n -> line.filePath().equals(n.filePath()) && n.startLine() == ln)
                        .findFirst()
                        .ifPresent(nodes::add);
            }
            result.put(line, nodes);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Step 2: build NodeConnections via BFS
    // -----------------------------------------------------------------------

    /**
     * Collects all unique anchor nodes from Step 1, then for every ordered
     * pair (A, B) where A ≠ B runs BFS(A→B, maxDepth={@value #MAX_BFS_DEPTH}).
     * A {@link NodeConnection} is emitted only when a path exists.
     *
     * <p>Intermediate nodes returned by BFS are stored in the connection
     * and are <b>not</b> used as anchors themselves.
     */
    private List<NodeConnection> buildConnections(
            Map<ChangedLine, Set<GraphNode>> lineToNodes, ProjectGraph graph) {

        // Unique anchor nodes — only from changed lines, not from intermediates
        Set<GraphNode> anchorNodes = lineToNodes.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<GraphNode> anchors = new ArrayList<>(anchorNodes);
        List<NodeConnection> connections = new ArrayList<>();

        for (int i = 0; i < anchors.size(); i++) {
            for (int j = 0; j < anchors.size(); j++) {
                if (i == j) continue;
                GraphNode a = anchors.get(i);
                GraphNode b = anchors.get(j);

                Optional<Set<GraphNode>> intermediates = bfsIntermediates(graph, a, b, MAX_BFS_DEPTH);
                intermediates.ifPresent(nodes -> {
                    connections.add(new NodeConnection(a, b, nodes));
                    log.debug("Connection found: {} -> {} (intermediates: {})",
                            a.id(), b.id(), nodes.stream().map(GraphNode::id).toList());
                });
            }
        }
        return connections;
    }

    /**
     * BFS from {@code from} to {@code to} with a maximum depth of {@code maxDepth}.
     *
     * <p>Uses {@code graph.edgesFrom} — the adjacency list keyed by caller node ID.
     * Each {@link GraphEdge} contributes one hop: caller → callee.
     *
     * <p>Returns the set of intermediate nodes on the shortest path
     * (empty set if {@code from} and {@code to} are directly connected),
     * or {@link Optional#empty()} if no path exists within {@code maxDepth} hops.
     *
     * @param graph    the AST project graph
     * @param from     BFS start node
     * @param to       target node
     * @param maxDepth maximum number of hops to explore
     */
    Optional<Set<GraphNode>> bfsIntermediates(
            ProjectGraph graph, GraphNode from, GraphNode to, int maxDepth) {

        if (from.equals(to)) return Optional.empty();

        // parent map: nodeId → parentId on the shortest discovered path
        Map<String, String> parent = new LinkedHashMap<>();
        // depth map: nodeId → hop count from 'from'
        Map<String, Integer> depth = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();

        queue.add(from.id());
        depth.put(from.id(), 0);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            int d = depth.get(currentId);
            if (d >= maxDepth) continue;

            for (GraphEdge edge : graph.edgesFrom.getOrDefault(currentId, List.of())) {
                String nextId = edge.callee();
                if (nextId == null || depth.containsKey(nextId)) continue;

                parent.put(nextId, currentId);
                depth.put(nextId, d + 1);

                if (nextId.equals(to.id())) {
                    // Path found — reconstruct intermediates (exclude from and to)
                    return Optional.of(reconstructIntermediates(parent, from.id(), to.id(), graph));
                }
                queue.add(nextId);
            }
        }
        return Optional.empty();
    }

    /**
     * Walks the {@code parent} map backwards from {@code toId} to {@code fromId}
     * and collects all nodes on the path except the two endpoints.
     */
    private Set<GraphNode> reconstructIntermediates(
            Map<String, String> parent, String fromId, String toId, ProjectGraph graph) {

        Deque<String> path = new ArrayDeque<>();
        String cur = toId;
        while (parent.containsKey(cur)) {
            cur = parent.get(cur);
            if (!cur.equals(fromId)) path.addFirst(cur);
        }
        Set<GraphNode> intermediates = new LinkedHashSet<>();
        for (String id : path) {
            GraphNode node = graph.nodes.get(id);
            if (node != null) intermediates.add(node);
        }
        return intermediates;
    }

    // -----------------------------------------------------------------------
    // Step 3: build ChangeGroups from connections
    // -----------------------------------------------------------------------

    /**
     * Each {@link NodeConnection}(A, B, intermediates) produces one
     * {@link ChangeGroup}:
     * <ul>
     *   <li>{@code changedLines} — only lines whose anchor nodes intersect
     *       {A, B}. Intermediate nodes are intentionally excluded from this
     *       set; they represent context, not changes.</li>
     *   <li>{@code intermediateNodes} — the BFS intermediates stored as
     *       read-only context on the group.</li>
     * </ul>
     * A line may appear in multiple groups when its anchor node participates
     * in multiple connections (intentional duplication by design).
     *
     * <p>After processing all connections:
     * <ul>
     *   <li>Lines with resolved nodes that matched no connection become
     *       singleton groups.</li>
     *   <li>Lines without any resolvable AST node are gathered into
     *       per-file fallback groups.</li>
     * </ul>
     */
    private List<ChangeGroup> buildGroups(
            List<NodeConnection> connections,
            Map<ChangedLine, Set<GraphNode>> lineToNodes,
            Set<ChangedLine> allLines) {

        List<ChangeGroup> result = new ArrayList<>();
        Set<ChangedLine> coveredLines = new HashSet<>();

        for (NodeConnection conn : connections) {
            // Only anchor nodes A and B — intermediates do NOT drive line selection
            Set<GraphNode> anchorSet = new HashSet<>();
            anchorSet.add(conn.node1());
            anchorSet.add(conn.node2());

            List<ChangedLine> groupLines = new ArrayList<>();
            for (Map.Entry<ChangedLine, Set<GraphNode>> entry : lineToNodes.entrySet()) {
                if (!Collections.disjoint(entry.getValue(), anchorSet)) {
                    groupLines.add(entry.getKey());
                }
            }
            if (groupLines.isEmpty()) continue;

            groupLines.sort(Comparator.comparingInt(
                    l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber()));

            String primaryFile = resolvePrimaryFile(groupLines, conn.node1());

            result.add(ChangeGroup.ofAst(
                    "G" + groupCounter.incrementAndGet(),
                    primaryFile,
                    groupLines,
                    new ArrayList<>(),
                    new ArrayList<>(conn.intermediateNodes())));

            coveredLines.addAll(groupLines);
        }

        // Singleton groups for anchor-resolved lines that matched no connection
        for (Map.Entry<ChangedLine, Set<GraphNode>> entry : lineToNodes.entrySet()) {
            ChangedLine line = entry.getKey();
            if (!entry.getValue().isEmpty() && !coveredLines.contains(line)) {
                result.add(ChangeGroup.of(
                        "G" + groupCounter.incrementAndGet(),
                        line.filePath(),
                        List.of(line),
                        new ArrayList<>()));
                coveredLines.add(line);
            }
        }

        // Per-file fallback for lines with no resolvable AST node
        Map<String, List<ChangedLine>> unresolvedByFile = new LinkedHashMap<>();
        for (ChangedLine line : allLines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) continue;
            if (!coveredLines.contains(line)) {
                unresolvedByFile.computeIfAbsent(line.filePath(), k -> new ArrayList<>()).add(line);
            }
        }
        for (Map.Entry<String, List<ChangedLine>> entry : unresolvedByFile.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(
                    l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber()));
            result.add(ChangeGroup.of(
                    "G" + groupCounter.incrementAndGet(),
                    entry.getKey(),
                    entry.getValue(),
                    new ArrayList<>()));
        }

        return result;
    }

    /**
     * Determines the primary file for a group as the file that contributes
     * the most non-CONTEXT changed lines. Falls back to {@code node1}'s file.
     */
    private static String resolvePrimaryFile(List<ChangedLine> lines, GraphNode node1) {
        return lines.stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .collect(Collectors.groupingBy(ChangedLine::filePath, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(node1.filePath());
    }
}
