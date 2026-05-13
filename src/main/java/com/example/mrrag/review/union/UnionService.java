package com.example.mrrag.review.union;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.UnionLine;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class UnionService {

    /**
     * Node kinds that act as broad structural containers (CLASS, INTERFACE, ANNOTATION type).
     * Two lines sharing only a container node are not semantically related — they merely
     * happen to live in the same type declaration. Excluding these from the union index
     * prevents spurious merging of unrelated changed lines.
     *
     * <p>METHOD, CONSTRUCTOR, LAMBDA, FIELD, VARIABLE and all other fine-grained kinds
     * are intentionally kept: a shared METHOD node means both lines are inside the same
     * (possibly short) method body, which is a meaningful co-location signal.
     */
    private static final Set<NodeKind> CONTAINER_KINDS = Set.of(
            NodeKind.CLASS,
            NodeKind.INTERFACE,
            NodeKind.ANNOTATION
    );

    /**
     * Node kinds that represent callable units (methods, constructors).
     * These are used as bridges in the union graph only when at least one changed line
     * actually falls inside their body — i.e. the method itself was modified.
     * This prevents spurious CROSS_SCOPE groups caused by unrelated changed lines that
     * merely <em>call</em> the same utility method (e.g. {@code $panel}, {@code $select})
     * without changing it.
     *
     * <p>FIELD, VARIABLE, PARAMETER and LAMBDA are intentionally excluded from this set:
     * sharing a field reference or a local variable is a genuine co-location signal even
     * when the field/variable declaration itself was not changed.
     */
    private static final Set<NodeKind> CALLABLE_KINDS = Set.of(
            NodeKind.METHOD,
            NodeKind.CONSTRUCTOR
    );

    public List<UnionLine> buildUnionLines(Map<ChangedLine, Set<GraphNode>> lineToNodes) {
        List<Map.Entry<ChangedLine, Set<GraphNode>>> entries = new ArrayList<>(lineToNodes.entrySet());
        int n = entries.size();

        if (n == 0) {
            return List.of();
        }

        // Collect IDs of METHOD/CONSTRUCTOR nodes whose body contains at least one changed line.
        // Only these callable nodes may act as bridges in the union index.
        Set<String> changedCallableIds = new HashSet<>();
        for (Map.Entry<ChangedLine, Set<GraphNode>> entry : entries) {
            ChangedLine line = entry.getKey();
            for (GraphNode node : entry.getValue()) {
                if (CALLABLE_KINDS.contains(node.kind()) && lineBelongsToNode(line, node)) {
                    changedCallableIds.add(node.id());
                }
            }
        }

        DSU dsu = new DSU(n);

        // nodeId -> index of first line that owns this node
        Map<String, Integer> nodeOwner = new HashMap<>();

        for (int i = 0; i < n; i++) {
            for (GraphNode node : entries.get(i).getValue()) {
                if (CONTAINER_KINDS.contains(node.kind()) && entries.get(i).getValue().stream().anyMatch(it -> !CONTAINER_KINDS.contains(it.kind()))) continue;

                // For callable nodes (METHOD, CONSTRUCTOR): only use as a bridge when the
                // method body itself was changed. Otherwise the node is merely a callee
                // referenced from the changed line, not a co-location anchor.
                if (CALLABLE_KINDS.contains(node.kind()) && !changedCallableIds.contains(node.id())) continue;

                Integer prev = nodeOwner.putIfAbsent(node.id(), i);
                if (prev != null) {
                    dsu.union(i, prev);
                }
            }
        }

        // root -> list of indices in this group
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);
        }

        List<UnionLine> result = new ArrayList<>();
        for (List<Integer> group : groups.values()) {
            result.add(mergeGroup(group, entries));
        }

        return result;
    }

    /**
     * Returns {@code true} when the changed line's effective line number falls
     * within the node's declared source range ({@code startLine..endLine}).
     */
    private static boolean lineBelongsToNode(ChangedLine line, GraphNode node) {
        if (!Objects.equals(line.filePath(), node.filePath())) return false;
        int effective = line.lineNumber() > 0 ? line.lineNumber() : line.oldLineNumber();
        return effective >= node.startLine() && effective <= node.endLine();
    }

    private static UnionLine mergeGroup(
            List<Integer> indices,
            List<Map.Entry<ChangedLine, Set<GraphNode>>> entries
    ) {
        Set<ChangedLine> changedLines = new LinkedHashSet<>();
        Set<GraphNode> graphNodes = new LinkedHashSet<>();
        Map<GraphNode, List<ChangedLine>> nodeOrigins = new LinkedHashMap<>();

        for (int i : indices) {
            ChangedLine cl = entries.get(i).getKey();
            changedLines.add(cl);
            for (GraphNode node : entries.get(i).getValue()) {
                graphNodes.add(node);
                nodeOrigins.computeIfAbsent(node, k -> new ArrayList<>()).add(cl);
            }
        }

        String id = buildId(changedLines);
        return new UnionLine(id, changedLines, graphNodes, nodeOrigins);
    }

    /**
     * Builds a short human-readable id for a union group.
     *
     * <p>Format: {@code fileName:from} or {@code fileName:from-to} where
     * {@code from}/{@code to} are the min/max effective line numbers across
     * all changed lines in the group. The effective line number is
     * {@code lineNumber} when positive (ADD/CONTEXT), otherwise
     * {@code oldLineNumber} (DELETE).
     *
     * <p>Example: {@code UserService.java:54-55}, {@code Bar.java:120}
     */
    static String buildId(Collection<ChangedLine> lines) {
        // group by file name (last path segment)
        Map<String, IntSummaryStatistics> statsByFile = new LinkedHashMap<>();
        for (ChangedLine cl : lines) {
            String fileName = fileName(cl.filePath());
            int effective = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();
            statsByFile.computeIfAbsent(fileName, k -> new IntSummaryStatistics());
            statsByFile.get(fileName).accept(effective);
        }

        return statsByFile.entrySet().stream()
                .map(e -> {
                    String file = e.getKey();
                    int min = (int) e.getValue().getMin();
                    int max = (int) e.getValue().getMax();
                    return min == max ? file + ":" + min : file + ":" + min + "-" + max;
                })
                .collect(Collectors.joining("|"));
    }

    private static String fileName(String filePath) {
        int slash = filePath.lastIndexOf('/');
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}
