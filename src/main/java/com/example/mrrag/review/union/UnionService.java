package com.example.mrrag.review.union;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.UnionLine;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class UnionService {
    public List<UnionLine> buildUnionLines(Map<ChangedLine, Set<GraphNode>> lineToNodes) {
        List<Map.Entry<ChangedLine, Set<GraphNode>>> entries = new ArrayList<>(lineToNodes.entrySet());
        int n = entries.size();

        if (n == 0) {
            return List.of();
        }

        DSU dsu = new DSU(n);

        // nodeId -> индекс первого вхождения
        Map<String, Integer> nodeOwner = new HashMap<>();

        for (int i = 0; i < n; i++) {
            for (GraphNode node : entries.get(i).getValue()) {
                Integer prev = nodeOwner.putIfAbsent(node.id(), i);
                if (prev != null) {
                    dsu.union(i, prev);
                }
            }
        }

        // root -> список индексов в группе
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
        // GraphNode -> все ChangedLine, из которых нода была резолвлена.
        // Одна нода может входить в несколько ChangedLine (например ADD и DELETE),
        // поэтому собираем полный список через computeIfAbsent.
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
