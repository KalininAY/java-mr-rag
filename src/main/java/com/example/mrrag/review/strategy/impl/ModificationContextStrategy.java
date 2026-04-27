package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Collects context when a {@link UnionLine} contains both ADD and DELETE lines,
 * or spans multiple files.
 *
 * <p>For every ADD/DELETE line, finds the smallest enclosing METHOD node and
 * adds a {@link EnrichmentSnippet.SnippetType#METHOD_BODY} snippet per unique method.
 * Then delegates to {@link AdditionContextStrategy} and {@link DeletionContextStrategy}
 * for cross-reference snippets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModificationContextStrategy implements ContextStrategy {

    private final AdditionContextStrategy additionStrategy;
    private final DeletionContextStrategy deletionStrategy;

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        boolean hasAdd = union.changedLines().stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
        boolean hasDel = union.changedLines().stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);

        // Only handle unions with both ADD and DELETE (or multi-file)
        boolean multiFile = union.changedLines().stream()
                .map(ChangedLine::filePath).distinct().count() > 1;
        if (!((hasAdd && hasDel) || multiFile)) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();

        // 1. Enclosing method bodies for every ADD/DELETE line
        collectContainingMethods(union, sourceGraph, snippets);

        // 2. Cross-reference snippets for ADD lines
        if (snippets.size() < maxSnippetsPerGroup) {
            additionStrategy.collectContext(union, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        // 3. Deletion-impact snippets for DELETE lines
        if (snippets.size() < maxSnippetsPerGroup) {
            deletionStrategy.collectContext(union, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        log.debug("ModificationContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }

    private void collectContainingMethods(
            UnionLine union, ProjectGraph graph, List<EnrichmentSnippet> snippets) {

        Set<String> seenMethodIds = new LinkedHashSet<>();

        List<ChangedLine> changedLines = union.changedLines().stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .filter(l -> l.lineNumber() > 0 || l.oldLineNumber() > 0)
                .toList();

        for (ChangedLine cl : changedLines) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            String file = AstGraphUtils.normalizeFilePath(cl.filePath(), graph);
            int line = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();

            graph.nodesAtLine(file, line).stream()
                    .filter(n -> n.kind() == NodeKind.METHOD)
                    .min(Comparator.comparingInt(n -> n.endLine() - n.startLine()))
                    .ifPresent(method -> {
                        if (snippets.size() >= maxSnippetsPerGroup) return;
                        if (!seenMethodIds.add(method.id())) return;

                        int end = Math.min(method.endLine(),
                                method.startLine() + maxSnippetLines - 1);
                        snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.METHOD_BODY,
                                method.filePath(),
                                method.startLine(),
                                end,
                                method.simpleName(),
                                method.sourceSnippet(),
                                "Body of enclosing method '" + method.simpleName() + "'"
                        ));
                    });
        }
    }
}
