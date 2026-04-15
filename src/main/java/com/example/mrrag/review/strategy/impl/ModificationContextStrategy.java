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
 * Strategy for {@link ChangeType#MODIFICATION} and {@link ChangeType#CROSS_SCOPE}.
 *
 * <p>For every changed ADD/DELETE line across all files in the group, finds
 * the smallest enclosing METHOD node and adds a {@link EnrichmentSnippet.SnippetType#METHOD_BODY}
 * snippet for each unique method found. This gives the reviewer the full
 * surrounding context without CONTEXT-line noise in the diff.
 *
 * <p>After collecting enclosing methods, also delegates to
 * {@link AdditionContextStrategy} and {@link DeletionContextStrategy} for
 * cross-reference snippets on ADD/DELETE edges.
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
    public List<EnrichmentSnippet> collectContext(
            ChangeGroup group, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        List<EnrichmentSnippet> snippets = new ArrayList<>();

        // 1. Enclosing method bodies for every ADD/DELETE line (all files)
        collectContainingMethods(group, sourceGraph, snippets);

        // 2. Cross-reference snippets for ADD lines
        if (snippets.size() < maxSnippetsPerGroup) {
            additionStrategy.collectContext(group, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        // 3. Deletion-impact snippets for DELETE lines
        if (snippets.size() < maxSnippetsPerGroup) {
            deletionStrategy.collectContext(group, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        log.debug("ModificationContextStrategy: group={} snippets={}", group.id(), snippets.size());
        return filterAlreadyInDiff(group, snippets);
    }

    /**
     * Iterates over <b>all</b> ADD/DELETE lines in the group (across all files),
     * finds the smallest enclosing METHOD node for each line, and adds a
     * {@link EnrichmentSnippet.SnippetType#METHOD_BODY} snippet per unique method.
     *
     * <p>Using the smallest enclosing method (min span) avoids accidentally
     * picking an outer class or lambda wrapper when nested methods exist.
     */
    private void collectContainingMethods(
            ChangeGroup group, ProjectGraph graph, List<EnrichmentSnippet> snippets) {

        // Track already-added method node IDs to avoid duplicates
        Set<String> seenMethodIds = new LinkedHashSet<>();

        List<ChangedLine> changedLines = group.changedLines().stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .filter(l -> l.lineNumber() > 0 || l.oldLineNumber() > 0)
                .toList();

        for (ChangedLine cl : changedLines) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            String file = AstGraphUtils.normalizeFilePath(cl.filePath(), graph);
            int line = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();

            graph.nodesAtLine(file, line).stream()
                    .filter(n -> n.kind() == NodeKind.METHOD)
                    // smallest span = most specific enclosing method
                    .min(Comparator.comparingInt(n -> n.endLine() - n.startLine()))
                    .ifPresent(method -> {
                        if (snippets.size() >= maxSnippetsPerGroup) return;
                        if (!seenMethodIds.add(method.id())) return; // already added

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
