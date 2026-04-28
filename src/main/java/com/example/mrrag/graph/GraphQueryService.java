package com.example.mrrag.graph;

import com.example.mrrag.graph.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    public List<GraphNode> getNodesWithLine(String filePath, int line, ProjectGraph graph) {
        if (line <= 0) return List.of();

        Set<GraphNode> result = new LinkedHashSet<>();

        //все ноды объявления, которые содержат строку line по файлу
        var partitioned = graph.nodes.values().stream()
                .filter(n -> filePath.equals(n.filePath()) && line >= n.startLine() && line <= n.endLine())
                .collect(Collectors.partitioningBy(n -> n.kind().isHasBody()));

        var withoutBody = partitioned.get(false);
        var withBody = partitioned.get(true);

        //Все ноды, вызовы которых содержат line по файлу
        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge edge : edges) {
                if (edge.startLine() <= line && edge.endLine() >= line && filePath.equals(edge.filePath())) {
                    GraphNode callee = graph.nodes.get(edge.callee());
                    GraphNode caller = graph.nodes.get(edge.caller());
                    if (callee != null) withoutBody.add(callee);
                    if (callee != null) withoutBody.add(caller);
                }
            }
        }

        // Если нет узлов без тела (переменных, полей, ...),
        // то добавляем узел с самым коротким телом (метод, лямбда, класс, ...)
        //сделана для структурных строк, исходя из предположения: структурные строки не имеют нод
        if (withoutBody.isEmpty()) {
            withBody.stream()
                    .filter(Objects::nonNull)
                    .min(Comparator.comparingInt(n -> n.sourceSnippet().length()))
                    .ifPresent(result::add);
        } else {
            // Если есть узлы без тела, то добавляем все узлы без тела
            result.addAll(withoutBody);
        }

        withBody.stream()
                .filter(Objects::nonNull)
                .filter(it-> it.kind() == NodeKind.METHOD || it.kind() == NodeKind.LAMBDA || it.kind() == NodeKind.CONSTRUCTOR)
                .min(Comparator.comparingInt(n -> n.sourceSnippet().length()))
                .ifPresent(result::add);

        return List.copyOf(result);
    }

    public Set<String> findMovedMethodIds(ProjectGraph source, ProjectGraph target) {
        Set<String> moved = new HashSet<>();
        for (GraphNode sn : source.nodes.values()) {
            if (sn.kind() != NodeKind.METHOD) continue;
            GraphNode tn = target.nodes.get(sn.id());
            if (tn == null) continue;
            if (sn.bodyHash() == null || !sn.bodyHash().equals(tn.bodyHash())) continue;
            if (sn.filePath().equals(tn.filePath()) && sn.startLine() == tn.startLine()) continue;
            moved.add(sn.id());
            log.debug("MOVED method: {} from {}:{} to {}:{}",
                    sn.id(), tn.filePath(), tn.startLine(), sn.filePath(), sn.startLine());
        }
        return moved;
    }

    public Set<String> findMovedFieldIds(ProjectGraph source, ProjectGraph target) {
        Set<String> moved = new HashSet<>();
        for (GraphNode sn : source.nodes.values()) {
            if (sn.kind() != NodeKind.FIELD) continue;
            GraphNode tn = target.nodes.get(sn.id());
            if (tn == null) continue;
            String sSig = sn.declarationSnippet() != null ? sn.declarationSnippet() : "";
            String tSig = tn.declarationSnippet() != null ? tn.declarationSnippet() : "";
            if (!sSig.equals(tSig)) continue;
            if (sn.filePath().equals(tn.filePath()) && sn.startLine() == tn.startLine()) continue;
            moved.add(sn.id());
            log.debug("MOVED field: {} from {}:{} to {}:{}",
                    sn.id(), tn.filePath(), tn.startLine(), sn.filePath(), sn.startLine());
        }
        return moved;
    }

    public Set<LineKey> methodLineRanges(ProjectGraph graph, Set<String> methodIds) {
        Set<LineKey> set = new HashSet<>();
        for (String id : methodIds) {
            GraphNode n = graph.nodes.get(id);
            if (n == null) continue;
            for (int i = n.startLine(); i <= n.endLine(); i++) {
                set.add(new LineKey(n.filePath(), i));
            }
        }
        return set;
    }

    public Set<LineKey> fieldDeclLines(ProjectGraph graph, Set<String> fieldIds) {
        Set<LineKey> set = new HashSet<>();
        for (String id : fieldIds) {
            GraphNode n = graph.nodes.get(id);
            if (n != null) set.add(new LineKey(n.filePath(), n.startLine()));
        }
        return set;
    }

    public record LineKey(String filePath, int line) {
    }
}
