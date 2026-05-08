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


    /**
     * Возвращает узлы графа, участвующие в указанной строке файла.
     *
     * <p>Стратегия по слоям:
     * <ol>
     *   <li><b>Якорь</b> — наименьший вмещающий METHOD/LAMBDA/CONSTRUCTOR
     *       ({@code startLine <= line <= endLine}). Связывает строки одного метода.</li>
     *   <li><b>Участники</b> — callee рёбер  диапазон содержит строку.
     *       Связывает строки через общую вызываемую сущность.</li>
     *   <li><b>Javadoc-проброс</b> — если найденная нода имеет kind==JAVADOC,
     *       она заменяется владельцем через входящее ребро HAS_JAVADOC.
     *       Это позволяет корректно привязать изменённую строку javadoc-комментария
     *       к методу/классу/полю, которому он принадлежит.</li>
     *   <li><b>Fallback</b> — если ноды не найдены (структурная строка: скобки, аннотации класса),
     *       добавляется наименьший вмещающий узел любого вида включая CLASS/INTERFACE.</li>
     * </ol>
     *
     * <p>CLASS/INTERFACE не используются как якорь — слишком широкий охват.
     * FIELD/VARIABLE возвращаются только как callee рёбер.
     *
     * @param filePath путь к файлу (repo-relative)
     * @param line     1-based номер строки; при {@code <= 0} возвращает пустой список
     * @param graph    граф проекта
     * @return неизменяемый список узлов; никогда не {@code null}
     */
    public List<GraphNode> getNodesWithLine(String filePath, int line, ProjectGraph graph) {
        if (line <= 0) return List.of();

        Set<GraphNode> result = new LinkedHashSet<>();

        // Слой 1: якорь — наименьший вмещающий метод/лямбда/конструктор
        graph.nodes.values().stream()
                .filter(n -> filePath.equals(n.filePath())
                        && line >= n.startLine() && line <= n.endLine())
                .forEach(result::add);

        // Слой 2: callee рёбер, участвующих в данной строке
        graph.edgesFrom.values().stream()
                .flatMap(Collection::stream)
                .filter(e -> filePath.equals(e.filePath())
                        && e.startLine() <= line && e.endLine() >= line)
                .map(e -> graph.nodes.get(e.callee()))
                .filter(Objects::nonNull)
                .forEach(result::add);

        // Слой 3: Javadoc-пробрось — для каждой JAVADOC-ноды добавляем её владельца.
        // Сама JAVADOC-нода остаётся в результате — она несёт sourceSnippet с текстом комментария.
        new ArrayList<>(result).stream()
                .filter(n -> n.kind() == NodeKind.JAVADOC)
                .forEach(jdoc ->
                        graph.incoming(jdoc.id(), EdgeKind.HAS_JAVADOC).stream()
                                .map(e -> graph.nodes.get(e.caller()))
                                .filter(Objects::nonNull)
                                .forEach(result::add));

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
