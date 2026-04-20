package com.example.mrrag.graph;

import com.example.mrrag.graph.model.*;
import com.example.mrrag.review.model.ChangedLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    public List<GraphNode> methodsInFile(ProjectGraph graph, String relPath) {
        return graph.nodes.values().stream()
                .filter(n -> n.kind() == NodeKind.METHOD && relPath.equals(n.filePath()))
                .toList();
    }

    public Optional<GraphNode> findContainingMethod(ProjectGraph graph, String relPath, int lineNo) {
        return methodsInFile(graph, relPath).stream()
                .filter(m -> m.startLine() <= lineNo && m.endLine() >= lineNo)
                .min(Comparator.comparingInt(m -> m.endLine() - m.startLine()));
    }

    public Optional<GraphNode> findTopLevelType(ProjectGraph graph, String relPath) {
        List<GraphNode> types = graph.nodes.values().stream()
                .filter(n -> (n.kind() == NodeKind.CLASS || n.kind() == NodeKind.INTERFACE)
                        && relPath.equals(n.filePath()))
                .toList();
        return types.size() == 1 ? Optional.of(types.get(0)) : Optional.empty();
    }

    public List<GraphNode> getNodesWithLine(String filePath, int line, ProjectGraph graph) {
        if (line <= 0) return List.of();

        Set<GraphNode> result = new LinkedHashSet<>();

        graph.nodes.values().stream()
                .filter(n -> filePath.equals(n.filePath()) && n.startLine() == line)
                .forEach(result::add);

        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge edge : edges) {
                if (edge.startLine() <= line && edge.endLine() >= line && filePath.equals(edge.filePath())) {
                    GraphNode callee = graph.nodes.get(edge.callee());
                    if (callee != null) result.add(callee);
                }
            }
        }

        return List.copyOf(result);
    }

    public static Optional<String> resolveImportSimpleName(String content) {
        if (content == null) return Optional.empty();
        Matcher m = IMPORT_PATTERN.matcher(content);
        if (!m.find()) return Optional.empty();
        String fqn = m.group(1);
        int dot = fqn.lastIndexOf('.');
        String simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        if ("*".equals(simpleName)) return Optional.empty();
        return Optional.of(simpleName);
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

    public Set<String> astKeysForLines(ProjectGraph graph, String relPath,
                                       Set<Integer> changedLines) {
        Set<String> result = new HashSet<>();
        if (changedLines.isEmpty()) return result;

        for (GraphNode n : graph.nodes.values()) {
            if (!relPath.equals(n.filePath())) continue;
            if (changedLines.contains(n.startLine())) result.add(n.id());
        }

        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge e : edges) {
                if (!relPath.equals(e.filePath())) continue;
                boolean intersects = changedLines.stream().anyMatch(line -> e.startLine() <= line && e.endLine() >= line);
                if (intersects && e.callee() != null) {
                    result.add(e.callee());
                }
            }
        }
        return result;
    }

    public record LineKey(String filePath, int line) {
    }
}
