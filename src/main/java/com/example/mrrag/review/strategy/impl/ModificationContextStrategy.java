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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Strategy for {@link ChangeType#MODIFICATION} and {@link ChangeType#CROSS_SCOPE}.
 *
 * <p>Provides the body of the enclosing method so the reviewer sees the
 * full context of the in-place change. Also delegates to both
 * {@link AdditionContextStrategy} and {@link DeletionContextStrategy}
 * for edges on ADD/DELETE lines respectively.
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
    public boolean supports(ChangeType changeType) {
        return changeType == ChangeType.MODIFICATION || changeType == ChangeType.CROSS_SCOPE;
    }

    @Override
    public List<EnrichmentSnippet> collectContext(ChangeGroup group, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        List<EnrichmentSnippet> snippets = new ArrayList<>();

        // 1. Enclosing method body
        collectContainingMethod(group, sourceGraph, snippets);

        // 2. Edges on ADD lines (re-use addition strategy)
        if (snippets.size() < maxSnippetsPerGroup) {
            List<EnrichmentSnippet> addCtx = additionStrategy.collectContext(
                    group, sourceGraph, targetGraph);
            addCtx.stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        // 3. Deleted declarations impact (re-use deletion strategy)
        if (snippets.size() < maxSnippetsPerGroup) {
            List<EnrichmentSnippet> delCtx = deletionStrategy.collectContext(
                    group, sourceGraph, targetGraph);
            delCtx.stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        log.debug("ModificationContextStrategy: group={} snippets={}", group.id(), snippets.size());
        return snippets;
    }

    private void collectContainingMethod(ChangeGroup group, ProjectGraph graph, List<EnrichmentSnippet> snippets) {
        if (snippets.size() >= maxSnippetsPerGroup) return;

        ChangedLine first = group.changedLines().stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .filter(l -> l.lineNumber() > 0 || l.oldLineNumber() > 0)
                .findFirst().orElse(null);
        if (first == null) return;

        String file = AstGraphUtils.normalizeFilePath(first.filePath(), graph);
        int line = first.lineNumber() > 0 ? first.lineNumber() : first.oldLineNumber();

        graph.nodesAtLine(file, line).stream()
                .filter(n -> n.kind() == NodeKind.METHOD)
                .min(Comparator.comparingInt(n -> n.endLine() - n.startLine()))
                .ifPresent(method -> {
                    if (snippets.size() >= maxSnippetsPerGroup) return;
                    int end = Math.min(method.endLine(), method.startLine() + maxSnippetLines - 1);
                    snippets.add(new EnrichmentSnippet(
                            EnrichmentSnippet.SnippetType.METHOD_BODY,
                            method.filePath(), method.startLine(), end, method.simpleName(),
                            method.sourceSnippet(),
                            "Body of enclosing method '" + method.simpleName() + "'"
                    ));
                });
    }

    private List<String> readLines(Path repoDir, String relPath, int from, int to) {
        Path file = repoDir.resolve(relPath);
        if (!Files.exists(file)) return List.of();
        try {
            List<String> all = Files.readAllLines(file);
            int start = Math.max(0, from - 1);
            int end = Math.min(all.size(), to);
            if (start >= end) return List.of();
            return all.subList(start, end).stream()
                    .map(l -> l.length() > 200 ? l.substring(0, 200) + "..." : l)
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return List.of();
        }
    }
}
