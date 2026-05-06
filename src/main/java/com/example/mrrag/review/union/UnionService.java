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
                // Skip container nodes — they span entire type declarations and
                // would incorrectly merge unrelated lines inside the same class.
                if (CONTAINER_KINDS.contains(node.kind())) continue;

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

        String mergedId = indices.stream()
                .map(i -> entries.get(i).getKey().filePath()
                        + ":" + entries.get(i).getKey().lineNumber())
                .sorted()
                .collect(Collectors.joining("|"));

        for (int i : indices) {
            ChangedLine cl = entries.get(i).getKey();
            changedLines.add(cl);
            for (GraphNode node : entries.get(i).getValue()) {
                graphNodes.add(node);
                nodeOrigins.computeIfAbsent(node, k -> new ArrayList<>()).add(cl);
            }
        }

        return new UnionLine(mergedId, changedLines, graphNodes, nodeOrigins);
    }
}
