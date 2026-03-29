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
 * <p>Enrichment strategies:
 * <ol>
 *   <li><b>Changed method declaration</b> – find all callers (cross-file)</li>
 *   <li><b>Deleted declaration</b> – find all usages of the removed symbol</li>
 *   <li><b>Added method call</b> – add callee signature + body</li>
 *   <li><b>Changed call arguments</b> – show callee signature with param names</li>
 *   <li><b>Changed/added field access</b> – show field declaration + all write sites</li>
 *   <li><b>Changed variable</b> – show all usages of the variable in the same method scope</li>
 *   <li><b>Containing method body</b> – baseline context for the changed area</li>
 * </ol>
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

    private static final Pattern METHOD_CALL_PATTERN =
            Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
    private static final Pattern FIELD_ACCESS_PATTERN =
            Pattern.compile("(?:this\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=;,)]");
    private static final Pattern DECLARATION_PATTERN =
            Pattern.compile("(?:private|public|protected|static|final|\\s)\\s+" +
                    "([a-zA-Z_][a-zA-Z0-9_.<>]*)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=;({]");
    private static final Pattern VAR_DECL_PATTERN =
            Pattern.compile("(?:var|final\\s+\\w+|\\w+)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*=");

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public List<ChangeGroup> enrich(
            List<ChangeGroup> groups,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        for (ChangeGroup group : groups) {
            enrichGroup(group, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir);
        }
        return groups;
    }

    private void enrichGroup(
            ChangeGroup group,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir
    ) {
        List<EnrichmentSnippet> snippets = group.enrichments(); // mutable list from ChangeGroup

        List<ChangedLine> added   = filterByType(group, ChangedLine.LineType.ADD);
        List<ChangedLine> deleted = filterByType(group, ChangedLine.LineType.DELETE);

        // 1. Changed method declaration -> callers
        strategyChangedMethodDeclaration(group, added, deleted, sourceIndex, sourceRepoDir, snippets);

        // 2. Deleted declaration -> usages
        strategyDeletedDeclaration(deleted, targetIndex, targetRepoDir, snippets);

        // 3. Added method calls -> callee signature + body
        strategyAddedMethodCalls(added, sourceIndex, sourceRepoDir, snippets);

        // 4. Changed call arguments -> callee parameter names
        strategyChangedArguments(added, deleted, sourceIndex, sourceRepoDir, snippets);

        // 5. Field accesses in changed lines -> declaration + write sites
        strategyFieldAccesses(added, deleted, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);

        // 6. Local variable changes -> usages in method
        strategyVariableUsages(added, deleted, sourceIndex, targetIndex, snippets);

        // 7. Containing method body (baseline context)
        strategyContainingMethod(group, sourceIndex, sourceRepoDir, snippets);

        trim(snippets);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: changed method declaration
    // -----------------------------------------------------------------------

    private void strategyChangedMethodDeclaration(
            ChangeGroup group, List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex, Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        int firstAdd = minLine(added, ChangedLine::lineNumber);
        int lastAdd  = maxLine(added, ChangedLine::lineNumber);
        if (firstAdd == 0) return;

        List<JavaIndexService.MethodInfo> changedMethods =
                indexService.findMethodsInRange(sourceIndex, group.primaryFile(), firstAdd, lastAdd);

        for (JavaIndexService.MethodInfo method : changedMethods) {
            if (full(snippets)) break;
            List<JavaIndexService.CallSite> callers = indexService.findCallSites(sourceIndex, method.qualifiedKey());
            if (!callers.isEmpty()) {
                snippets.add(new EnrichmentSnippet(
                        EnrichmentSnippet.SnippetType.METHOD_CALLERS,
                        method.filePath(), method.startLine(), method.endLine(), method.name(),
                        callers.stream().limit(10)
                                .map(cs -> cs.filePath() + ":" + cs.line() + "  " + cs.methodName() +
                                        "(" + String.join(", ", cs.argumentTexts()) + ")")
                                .toList(),
                        "All callers of changed method " + method.signature()
                ));
            }
            addMethodBodySnippet(method, sourceRepoDir, EnrichmentSnippet.SnippetType.METHOD_BODY, snippets);
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 2: deleted declaration -> usages
    // -----------------------------------------------------------------------

    private void strategyDeletedDeclaration(
            List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex targetIndex, Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        for (ChangedLine line : deleted) {
            if (full(snippets)) break;
            Matcher m = DECLARATION_PATTERN.matcher(line.content().trim());
            if (!m.find()) continue;
            String name = m.group(2);

            // Check field usages
            List<JavaIndexService.FieldAccess> accesses =
                    indexService.findFieldAccessesByName(targetIndex, name);
            if (!accesses.isEmpty()) {
                snippets.add(new EnrichmentSnippet(
                        EnrichmentSnippet.SnippetType.FIELD_USAGES,
                        line.filePath(), line.oldLineNumber(), line.oldLineNumber(), name,
                        accesses.stream().limit(10)
                                .map(a -> a.filePath() + ":" + a.line()).toList(),
                        "Field '" + name + "' usages in target branch (it was deleted)"
                ));
                continue;
            }

            // Check name usages
            List<JavaIndexService.NameUsage> usages =
                    indexService.findUsagesByName(targetIndex, name);
            if (!usages.isEmpty()) {
                snippets.add(new EnrichmentSnippet(
                        EnrichmentSnippet.SnippetType.VARIABLE_USAGES,
                        line.filePath(), line.oldLineNumber(), line.oldLineNumber(), name,
                        usages.stream().limit(10)
                                .map(u -> u.filePath() + ":" + u.line()).toList(),
                        "Symbol '" + name + "' usages in target branch (it was deleted)"
                ));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 3: added method calls -> callee signature
    // -----------------------------------------------------------------------

    private void strategyAddedMethodCalls(
            List<ChangedLine> added,
            JavaIndexService.ProjectIndex sourceIndex, Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seen = new HashSet<>();
        for (ChangedLine line : added) {
            if (full(snippets)) break;
            Matcher m = METHOD_CALL_PATTERN.matcher(line.content());
            while (m.find()) {
                String name = m.group(1);
                if (seen.contains(name) || isKeyword(name)) continue;
                seen.add(name);
                sourceIndex.methods.values().stream()
                        .filter(mi -> mi.name().equals(name))
                        .findFirst()
                        .ifPresent(mi -> addMethodSignatureSnippet(mi, sourceRepoDir, snippets));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 4: changed call arguments -> show callee parameter names
    // -----------------------------------------------------------------------

    /**
     * Detects when the same method call appears in both added and deleted lines
     * but with different arguments. Adds callee signature with parameter names so
     * LLM can evaluate whether the new args match the expected types.
     */
    private void strategyChangedArguments(
            List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex, Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> addedCalls  = extractMethodCallNames(added);
        Set<String> deletedCalls = extractMethodCallNames(deleted);
        // Intersection = same method called but line changed => args may differ
        Set<String> changedCalls = new HashSet<>(addedCalls);
        changedCalls.retainAll(deletedCalls);

        for (String name : changedCalls) {
            if (full(snippets)) break;
            sourceIndex.methods.values().stream()
                    .filter(mi -> mi.name().equals(name))
                    .findFirst()
                    .ifPresent(mi -> {
                        List<String> sigLines = new ArrayList<>();
                        sigLines.add(mi.signature() + "  // parameters:");
                        mi.parameters().forEach(p -> sigLines.add("    " + p));
                        snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.METHOD_SIGNATURE,
                                mi.filePath(), mi.startLine(), mi.startLine(), mi.name(),
                                sigLines,
                                "Arguments changed for call to '" + mi.name() +
                                        "' – verify new args match param types"
                        ));
                    });
        }
    }

    private Set<String> extractMethodCallNames(List<ChangedLine> lines) {
        Set<String> names = new HashSet<>();
        for (ChangedLine l : lines) {
            Matcher m = METHOD_CALL_PATTERN.matcher(l.content());
            while (m.find()) {
                String name = m.group(1);
                if (!isKeyword(name)) names.add(name);
            }
        }
        return names;
    }

    // -----------------------------------------------------------------------
    // Strategy 5: field accesses in changed lines
    // -----------------------------------------------------------------------

    /**
     * For fields referenced in changed lines: show field declaration and all write sites.
     * Helps LLM understand nullability, mutability, and threading concerns.
     */
    private void strategyFieldAccesses(
            List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir, Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> fieldNames = new HashSet<>();
        for (ChangedLine line : added) extractFieldNames(line.content(), fieldNames);
        for (ChangedLine line : deleted) extractFieldNames(line.content(), fieldNames);

        for (String fieldName : fieldNames) {
            if (full(snippets)) break;

            // Declaration in source
            List<JavaIndexService.FieldInfo> infos =
                    indexService.findFieldsByName(sourceIndex, fieldName);
            if (infos.isEmpty()) infos = indexService.findFieldsByName(targetIndex, fieldName);
            if (infos.isEmpty()) continue;

            JavaIndexService.FieldInfo fi = infos.get(0);
            List<String> declLines = readFileLines(
                    fi.filePath().startsWith("src") ? sourceRepoDir : sourceRepoDir,
                    fi.filePath(), fi.declarationLine(), fi.declarationLine());

            // Write sites (FieldAccess)
            List<JavaIndexService.FieldAccess> accesses =
                    indexService.findFieldAccessesByName(sourceIndex, fieldName);
            List<String> content = new ArrayList<>(declLines);
            if (!accesses.isEmpty()) {
                content.add("// usages:");
                accesses.stream().limit(8)
                        .forEach(a -> content.add("    " + a.filePath() + ":" + a.line()));
            }

            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.FIELD_USAGES,
                    fi.filePath(), fi.declarationLine(), fi.declarationLine(), fieldName,
                    content,
                    "Field '" + fieldName + "' declaration (type: " + fi.type() + ") and usages"
            ));
        }
    }

    private void extractFieldNames(String content, Set<String> result) {
        Matcher m = FIELD_ACCESS_PATTERN.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            if (!isKeyword(name) && name.length() > 1) result.add(name);
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 6: local variable changes -> usages in enclosing method
    // -----------------------------------------------------------------------

    /**
     * When a variable declaration or assignment is changed, show all other
     * places it is used within the same file, so LLM can assess downstream impact.
     */
    private void strategyVariableUsages(
            List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> varNames = new HashSet<>();
        for (ChangedLine line : added) extractVarNames(line.content(), varNames);
        for (ChangedLine line : deleted) extractVarNames(line.content(), varNames);

        for (String varName : varNames) {
            if (full(snippets)) break;
            // Find usages by simple name in source
            List<JavaIndexService.NameUsage> usages =
                    indexService.findUsagesByName(sourceIndex, varName);
            if (usages.size() <= 1) {
                // Also try target
                usages = indexService.findUsagesByName(targetIndex, varName);
            }
            if (usages.size() <= 1) continue; // nothing interesting if only used once

            List<String> lines = usages.stream().limit(10)
                    .map(u -> u.filePath() + ":" + u.line()).toList();
            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.VARIABLE_USAGES,
                    usages.get(0).filePath(),
                    usages.stream().mapToInt(JavaIndexService.NameUsage::line).min().orElse(0),
                    usages.stream().mapToInt(JavaIndexService.NameUsage::line).max().orElse(0),
                    varName, lines,
                    "All usages of changed variable '" + varName + "'"
            ));
        }
    }

    private void extractVarNames(String content, Set<String> result) {
        Matcher m = VAR_DECL_PATTERN.matcher(content.trim());
        if (m.find()) {
            String name = m.group(1);
            if (!isKeyword(name)) result.add(name);
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 7: containing method body (baseline context)
    // -----------------------------------------------------------------------

    private void strategyContainingMethod(
            ChangeGroup group,
            JavaIndexService.ProjectIndex sourceIndex, Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        int firstLine = group.changedLines().stream()
                .mapToInt(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                .filter(n -> n > 0).min().orElse(0);
        if (firstLine == 0) return;
        indexService.findContainingMethod(sourceIndex, group.primaryFile(), firstLine)
                .ifPresent(m -> addMethodBodySnippet(m, sourceRepoDir,
                        EnrichmentSnippet.SnippetType.METHOD_BODY, snippets));
    }

    // -----------------------------------------------------------------------
    // Snippet builders
    // -----------------------------------------------------------------------

    private void addMethodSignatureSnippet(
            JavaIndexService.MethodInfo method, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        List<String> lines = readFileLines(repoDir, method.filePath(),
                method.startLine(), Math.min(method.startLine() + 3, method.endLine()));
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_SIGNATURE,
                method.filePath(), method.startLine(), method.startLine(), method.name(),
                lines, "Signature of " + method.signature()
        ));
    }

    private void addMethodBodySnippet(
            JavaIndexService.MethodInfo method, Path repoDir,
            EnrichmentSnippet.SnippetType type, List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        int end = Math.min(method.endLine(), method.startLine() + maxSnippetLines - 1);
        List<String> lines = readFileLines(repoDir, method.filePath(), method.startLine(), end);
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                type, method.filePath(), method.startLine(), end, method.name(),
                lines, "Body of method " + method.signature()
        ));
    }

    private List<String> readFileLines(Path repoDir, String relPath, int from, int to) {
        Path file = repoDir.resolve(relPath);
        if (!Files.exists(file)) return List.of();
        try {
            List<String> all = Files.readAllLines(file);
            int start = Math.max(0, from - 1);
            int end   = Math.min(all.size(), to);
            if (start >= end) return List.of();
            return all.subList(start, end).stream()
                    .map(l -> l.length() > 200 ? l.substring(0, 200) + "..." : l)
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<ChangedLine> filterByType(ChangeGroup g, ChangedLine.LineType type) {
        return g.changedLines().stream().filter(l -> l.type() == type).toList();
    }

    private int minLine(List<ChangedLine> lines, java.util.function.ToIntFunction<ChangedLine> fn) {
        return lines.stream().mapToInt(fn).filter(n -> n > 0).min().orElse(0);
    }

    private int maxLine(List<ChangedLine> lines, java.util.function.ToIntFunction<ChangedLine> fn) {
        return lines.stream().mapToInt(fn).filter(n -> n > 0).max().orElse(0);
    }

    private boolean full(List<EnrichmentSnippet> snippets) {
        return snippets.size() >= maxSnippetsPerGroup;
    }

    private void trim(List<EnrichmentSnippet> snippets) {
        while (snippets.size() > maxSnippetsPerGroup) snippets.remove(snippets.size() - 1);
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "throw", "new",
            "assert", "synchronized", "instanceof", "super", "this", "null",
            "true", "false", "var"
    );

    private boolean isKeyword(String name) { return JAVA_KEYWORDS.contains(name); }
}
