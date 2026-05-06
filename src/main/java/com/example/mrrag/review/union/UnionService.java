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

    public List<UnionLine> buildUnionLines(Map<ChangedLine, Set<GraphNode>> lineToNodes) {
        List<Map.Entry<ChangedLine, Set<GraphNode>>> entries = new ArrayList<>(lineToNodes.entrySet());
        int n = entries.size();

        if (n == 0) {
            return List.of();
        }

        DSU dsu = new DSU(n);

        // nodeId -> index of first line that owns this node
        Map<String, Integer> nodeOwner = new HashMap<>();

        for (int i = 0; i < n; i++) {
            for (GraphNode node : entries.get(i).getValue()) {
                if (CONTAINER_KINDS.contains(node.kind()) && entries.get(i).getValue().stream().anyMatch(it -> !CONTAINER_KINDS.contains(it.kind()))) continue;

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
