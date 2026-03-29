package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import com.example.mrrag.model.EnrichmentSnippet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches ChangeGroups with contextual snippets from the indexed codebase.
 *
 * <p>For each group it analyses what kind of changes are present and adds:
 * <ul>
 *   <li>If a method call's arguments changed -&gt; method signature + first N lines of body</li>
 *   <li>If a line was deleted -&gt; check if it was a declaration and find usages</li>
 *   <li>If a method declaration changed -&gt; list all callers</li>
 *   <li>If a new method call was added -&gt; method signature</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextEnricher {

    private final JavaIndexService indexService;

    @Value("${app.enrichment.maxSnippetsPerGroup:10}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    // Regex patterns for quick detection in line content
    private static final Pattern METHOD_CALL_PATTERN =
            Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
    private static final Pattern DECLARATION_PATTERN =
            Pattern.compile("(private|public|protected|static|final|void|int|long|String|boolean|\\w+)\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=;(]");

    /**
     * Enrich all groups. Modifies the enrichments list of each ChangeGroup.
     *
     * @param groups       change groups to enrich (mutable - we add to their enrichments)
     * @param sourceIndex  index built from the source branch ("how it will look")
     * @param targetIndex  index built from the target branch ("baseline")
     * @param sourceRepoDir local path of source branch checkout
     * @param targetRepoDir local path of target branch checkout
     * @return enriched groups (same list, mutated)
     */
    public List<ChangeGroup> enrich(
            List<ChangeGroup> groups,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        for (ChangeGroup group : groups) {
            List<EnrichmentSnippet> snippets = new ArrayList<>();
            enrichGroup(group, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);
            // Replace the (originally empty) enrichments list
            // ChangeGroup is a record so we rebuild it
            var enriched = new ChangeGroup(group.id(), group.primaryFile(), group.changedLines(), snippets);
            // since ChangeGroup is a record (immutable), we need to update the list at caller side
            // we'll use a workaround: store in a parallel map and rebuild at the service level
            // For now store results back via the mutable list trick
            group.enrichments().addAll(snippets);
        }
        return groups;
    }

    private void enrichGroup(
            ChangeGroup group,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<ChangedLine> added = group.changedLines().stream()
                .filter(l -> l.type() == ChangedLine.LineType.ADD).toList();
        List<ChangedLine> deleted = group.changedLines().stream()
                .filter(l -> l.type() == ChangedLine.LineType.DELETE).toList();

        // --- 1. Changed method declaration (present in both added and deleted, same name) ---
        detectChangedMethodDeclaration(group, added, deleted, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);

        // --- 2. Deleted declarations -> find usages ---
        detectDeletedDeclarations(deleted, targetIndex, targetRepoDir, snippets);

        // --- 3. Added/modified method calls -> add callee signature ---
        detectAddedMethodCalls(added, sourceIndex, sourceRepoDir, snippets);

        // --- 4. Containing method context for the changed area ---
        addContainingMethodContext(group, sourceIndex, sourceRepoDir, snippets);

        // Trim to max
        while (snippets.size() > maxSnippetsPerGroup) {
            snippets.remove(snippets.size() - 1);
        }
    }

    // -----------------------------------------------------------------------
    // Detection strategies
    // -----------------------------------------------------------------------

    /** If a method signature was changed: find all callers of the old and new version. */
    private void detectChangedMethodDeclaration(
            ChangeGroup group,
            List<ChangedLine> added,
            List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        String file = group.primaryFile();

        // Find methods in source that overlap the changed lines
        int firstAddLine = added.stream().mapToInt(ChangedLine::lineNumber).filter(n -> n > 0).min().orElse(0);
        int lastAddLine = added.stream().mapToInt(ChangedLine::lineNumber).filter(n -> n > 0).max().orElse(0);
        if (firstAddLine == 0) return;

        List<JavaIndexService.MethodInfo> changedMethods =
                indexService.findMethodsInRange(sourceIndex, file, firstAddLine, lastAddLine);

        for (JavaIndexService.MethodInfo method : changedMethods) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            // Add callers from source index
            List<JavaIndexService.CallSite> callers = indexService.findCallSites(sourceIndex, method.qualifiedKey());
            if (!callers.isEmpty()) {
                List<String> callerLines = callers.stream()
                        .limit(10)
                        .map(cs -> cs.filePath() + ":" + cs.line() + "  calls  " + cs.methodName() + "(...)")
                        .toList();
                snippets.add(new EnrichmentSnippet(
                        EnrichmentSnippet.SnippetType.METHOD_CALLERS,
                        method.filePath(),
                        method.startLine(),
                        method.endLine(),
                        method.name(),
                        callerLines,
                        "All callers of changed method " + method.signature()
                ));
            }

            // Add the method body snippet from source branch
            addMethodBodySnippet(method, sourceRepoDir, EnrichmentSnippet.SnippetType.METHOD_BODY, snippets);
        }
    }

    /** For deleted lines that look like declarations, find all usages in target. */
    private void detectDeletedDeclarations(
            List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex targetIndex,
            Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        for (ChangedLine line : deleted) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            String content = line.content().trim();
            Matcher m = DECLARATION_PATTERN.matcher(content);
            if (m.find()) {
                String name = m.group(2);
                // Search usages in target index
                targetIndex.nameUsages.forEach((key, usages) -> {
                    if (key.endsWith(":" + name) && !usages.isEmpty()) {
                        List<String> usageLines = usages.stream()
                                .limit(10)
                                .map(u -> u.filePath() + ":" + u.line())
                                .toList();
                        snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.VARIABLE_USAGES,
                                line.filePath(),
                                line.oldLineNumber(),
                                line.oldLineNumber(),
                                name,
                                usageLines,
                                "Usages of deleted symbol '" + name + "' in target branch"
                        ));
                    }
                });
            }
        }
    }

    /** For added lines with method calls, add the callee's signature snippet. */
    private void detectAddedMethodCalls(
            List<ChangedLine> added,
            JavaIndexService.ProjectIndex sourceIndex,
            Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seenMethods = new HashSet<>();

        for (ChangedLine line : added) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            Matcher m = METHOD_CALL_PATTERN.matcher(line.content());
            while (m.find()) {
                String methodName = m.group(1);
                if (seenMethods.contains(methodName)) continue;
                // Skip common keywords that match the pattern
                if (isKeyword(methodName)) continue;
                seenMethods.add(methodName);

                // Find method in source index
                sourceIndex.methods.values().stream()
                        .filter(mi -> mi.name().equals(methodName))
                        .findFirst()
                        .ifPresent(mi -> addMethodSignatureSnippet(mi, sourceRepoDir, snippets));
            }
        }
    }

    /** Add the body of the method that contains the change, for broader context. */
    private void addContainingMethodContext(
            ChangeGroup group,
            JavaIndexService.ProjectIndex sourceIndex,
            Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (snippets.size() >= maxSnippetsPerGroup) return;

        String file = group.primaryFile();
        int firstLine = group.changedLines().stream()
                .mapToInt(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                .filter(n -> n > 0)
                .min().orElse(0);
        if (firstLine == 0) return;

        indexService.findContainingMethod(sourceIndex, file, firstLine)
                .ifPresent(method -> addMethodBodySnippet(method, sourceRepoDir,
                        EnrichmentSnippet.SnippetType.METHOD_SIGNATURE, snippets));
    }

    // -----------------------------------------------------------------------
    // Snippet builders
    // -----------------------------------------------------------------------

    private void addMethodSignatureSnippet(
            JavaIndexService.MethodInfo method,
            Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> lines = readFileLines(repoDir, method.filePath(),
                method.startLine(), Math.min(method.startLine() + 2, method.endLine()));
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_SIGNATURE,
                method.filePath(),
                method.startLine(),
                method.startLine(),
                method.name(),
                lines,
                "Signature of method " + method.signature()
        ));
    }

    private void addMethodBodySnippet(
            JavaIndexService.MethodInfo method,
            Path repoDir,
            EnrichmentSnippet.SnippetType type,
            List<EnrichmentSnippet> snippets
    ) {
        int endLine = Math.min(method.endLine(), method.startLine() + maxSnippetLines - 1);
        List<String> lines = readFileLines(repoDir, method.filePath(), method.startLine(), endLine);
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                type,
                method.filePath(),
                method.startLine(),
                endLine,
                method.name(),
                lines,
                "Body of method " + method.signature()
        ));
    }

    private List<String> readFileLines(Path repoDir, String relPath, int from, int to) {
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
            log.warn("Cannot read file {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "throw", "new",
            "assert", "synchronized", "instanceof", "super", "this"
    );

    private boolean isKeyword(String name) {
        return JAVA_KEYWORDS.contains(name);
    }
}
