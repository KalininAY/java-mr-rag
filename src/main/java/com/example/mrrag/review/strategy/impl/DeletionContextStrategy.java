package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects context for DELETE lines in a {@link UnionLine}.
 *
 * <p>Looks up the target (base) graph to find usages of what was removed,
 * helping the reviewer understand the impact of the deletion.
 *
 * <p>Uses {@link UnionLine#nodeOrigins()} — DELETE nodes are resolved
 * against {@code targetGraph}.
 */
@Slf4j
@Component
public class DeletionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Override
    public List<EnrichmentSnippet> collectContext(UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        List<EnrichmentSnippet> snippets = new ArrayList<>();

        List<ChangedLine> deleted = union.changedLines().stream()
                .filter(l -> l.type() == ChangedLine.LineType.DELETE)
                .toList();

        if (deleted.isEmpty()) return snippets;

        for (ChangedLine cl : deleted) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            String file = AstGraphUtils.normalizeFilePath(cl.filePath(), targetGraph);
            int oldLine = cl.oldLineNumber();
            if (oldLine <= 0) continue;

            List<GraphNode> declared = targetGraph.byLine
                    .getOrDefault(file + "#" + oldLine, List.of());

            for (GraphNode decl : declared) {
                if (snippets.size() >= maxSnippetsPerGroup) break;

                List<GraphEdge> usageEdges = targetGraph.incoming(decl.id());
                if (usageEdges.isEmpty()) continue;

                EnrichmentSnippet.SnippetType type = switch (decl.kind()) {
                    case METHOD -> EnrichmentSnippet.SnippetType.METHOD_CALLERS;
                    case FIELD -> EnrichmentSnippet.SnippetType.FIELD_USAGES;
                    default -> EnrichmentSnippet.SnippetType.VARIABLE_USAGES;
                };

                String usageLines = usageEdges.stream()
                        .filter(e -> e.kind() != EdgeKind.DECLARES)
                        .limit(10)
                        .map(e -> e.filePath() + ":" + e.startLine() + "-" + e.endLine())
                        .distinct()
                        .collect(Collectors.joining());

                if (usageLines.isBlank()) continue;

                snippets.add(new EnrichmentSnippet(
                        type,
                        decl.filePath(), decl.startLine(), decl.endLine(), decl.simpleName(),
                        usageLines,
                        decl.simpleName() + " is deleted but still referenced in " + usageLines.lines().count() + " place(s)"
                ));
            }
        }

        log.debug("DeletionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }
}
