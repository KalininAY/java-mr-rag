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
import java.util.function.ToIntFunction;

/**
 * Enriches ChangeGroups with contextual snippets from the indexed codebase.
 *
 * <p>Enrichment strategies (in priority order):
 * <ol>
 *   <li><b>METHOD_DECLARATION</b>  – any method <em>used</em> in changed lines gets its
 *       declaration lines: from {@code startLine} up to and including the line
 *       containing the opening brace {@code {}, so the full signature is always
 *       captured regardless of annotation count or multi-line parameter lists.</li>
 *   <li><b>FIELD_DECLARATION</b>   – any field accessed in changed lines gets its
 *       declaration line</li>
 *   <li><b>VARIABLE_DECLARATION</b>– any local variable whose name literally appears
 *       in the text of changed lines gets its declaration line (reads real file content)</li>
 *   <li><b>METHOD_CALLERS</b>      – if a method <em>declaration</em> was changed, list all
 *       callers</li>
 *   <li><b>FIELD_USAGES</b>        – if a field declaration was deleted, list all accesses</li>
 *   <li><b>VARIABLE_USAGES</b>     – if a variable declaration was deleted/changed, list all
 *       usages</li>
 *   <li><b>ARGUMENT_CONTEXT</b>    – if call arguments changed, show param names + types</li>
 *   <li><b>METHOD_BODY</b>         – baseline: body of the method containing the change</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextEnricher {

    private final JavaIndexService indexService;

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

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
        List<EnrichmentSnippet> snippets = group.enrichments();

        List<ChangedLine> added   = filterByType(group, ChangedLine.LineType.ADD);
        List<ChangedLine> deleted = filterByType(group, ChangedLine.LineType.DELETE);
        List<ChangedLine> all     = group.changedLines();

        // 1. Declarations for every method call in changed lines
        strategyMethodDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);

        // 2. Declarations for every field access in changed lines
        strategyFieldDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);

        // 3. Declarations for every local variable whose name appears in the changed text
        strategyVariableDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);

        // 4. If a method declaration changed -> list all callers
        strategyChangedMethodDeclaration(group, added, sourceIndex, sourceRepoDir, snippets);

        // 5. Deleted field/variable declaration -> usages
        strategyDeletedDeclaration(deleted, targetIndex, snippets);

        // 6. Changed call arguments -> param names + types
        strategyChangedArguments(added, deleted, sourceIndex, snippets);

        // 7. Containing method body (baseline context)
        strategyContainingMethod(group, sourceIndex, sourceRepoDir, snippets);

        trim(snippets);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: METHOD_DECLARATION for every called method in changed lines
    // -----------------------------------------------------------------------

    private void strategyMethodDeclarations(
            List<ChangedLine> all,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir, Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seen = new HashSet<>();
        Set<Integer> changedLineNos = changedLineNumbers(all);

        // Only consider call sites that belong to the files present in 'all'
        Set<String> changedFiles = changedFiles(all);

        sourceIndex.callSites.forEach((calleeKey, sites) -> {
            if (full(snippets)) return;
            boolean onChangedLine = sites.stream()
                    .anyMatch(cs -> changedFiles.contains(cs.filePath()) && changedLineNos.contains(cs.line()));
            if (!onChangedLine) return;
            if (seen.contains(calleeKey)) return;
            seen.add(calleeKey);

            JavaIndexService.MethodInfo decl =
                    sourceIndex.methods.getOrDefault(calleeKey, targetIndex.methods.get(calleeKey));
            if (decl == null) return;

            Path repoDir = sourceIndex.methods.containsKey(calleeKey) ? sourceRepoDir : targetRepoDir;
            addMethodDeclarationSnippet(decl, repoDir, snippets);
        });
    }

    /**
     * Reads the method declaration from {@code startLine} up to and including
     * the line that contains the opening brace {@code {}.
     * This correctly handles any number of annotations and multi-line parameter lists
     * without relying on a fixed line count.
     */
    private void addMethodDeclarationSnippet(
            JavaIndexService.MethodInfo m, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        Path file = repoDir.resolve(m.filePath());
        if (!Files.exists(file)) return;

        List<String> allLines;
        try {
            allLines = Files.readAllLines(file);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return;
        }

        // Collect lines from startLine until we hit the opening brace of the method body.
        // startLine is 1-based; method body starts at the line containing '{'.
        List<String> declLines = new ArrayList<>();
        int signatureEnd = m.startLine(); // will be updated
        for (int i = m.startLine() - 1; i < Math.min(allLines.size(), m.endLine()); i++) {
            String line = allLines.get(i);
            declLines.add(line.length() > 200 ? line.substring(0, 200) + "..." : line);
            signatureEnd = i + 1; // 1-based
            if (line.contains("{")) break;
        }

        if (declLines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                m.filePath(), m.startLine(), signatureEnd, m.name(),
                declLines,
                "Declaration of method '" + m.name() + "' used in changed lines"
        ));
    }

    // -----------------------------------------------------------------------
    // Strategy 2: FIELD_DECLARATION for every field accessed in changed lines
    // -----------------------------------------------------------------------

    private void strategyFieldDeclarations(
            List<ChangedLine> all,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir, Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> seen = new HashSet<>();
        Set<Integer> changedLineNos = changedLineNumbers(all);
        Set<String> changedFiles = changedFiles(all);

        sourceIndex.fieldAccesses.forEach((fieldKey, accesses) -> {
            if (full(snippets)) return;
            boolean onChangedLine = accesses.stream()
                    .anyMatch(fa -> changedFiles.contains(fa.filePath()) && changedLineNos.contains(fa.line()));
            if (!onChangedLine) return;
            if (seen.contains(fieldKey)) return;
            seen.add(fieldKey);

            JavaIndexService.FieldInfo decl =
                    sourceIndex.fields.getOrDefault(fieldKey, targetIndex.fields.get(fieldKey));
            if (decl == null) return;

            Path repoDir = sourceIndex.fields.containsKey(fieldKey) ? sourceRepoDir : targetRepoDir;
            addFieldDeclarationSnippet(decl, repoDir, snippets);
        });
    }

    private void addFieldDeclarationSnippet(
            JavaIndexService.FieldInfo f, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        List<String> lines = readLines(repoDir, f.filePath(), f.declarationLine(), f.declarationLine());
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.FIELD_DECLARATION,
                f.filePath(), f.declarationLine(), f.declarationLine(), f.name(),
                lines,
                "Declaration of field '" + f.name() + "' (type: " + f.type() + ") used in changed lines"
        ));
    }

    // -----------------------------------------------------------------------
    // Strategy 3: VARIABLE_DECLARATION for every local var used in changed lines
    //
    // Fix 1: filter by name literally appearing in the changed text (avoids
    //        surfacing variables from unrelated files/methods).
    // Fix 2: read real file content via readLines() instead of constructing a
    //        synthetic "filepath:line (type: X)" string.
    // -----------------------------------------------------------------------

    private void strategyVariableDeclarations(
            List<ChangedLine> all,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        // Collect all identifier tokens that appear literally in the changed line texts.
        // This prevents surfacing declarations for variables that SymbolSolver resolved
        // by accident from unrelated scopes.
        Set<String> namesInChangedText = extractIdentifiers(all);
        Set<String> seen = new HashSet<>();

        sourceIndex.nameUsagesBySimpleName.forEach((name, usages) -> {
            if (full(snippets)) return;
            // Only surface variables whose name actually appears in the changed text
            if (!namesInChangedText.contains(name)) return;
            if (seen.contains(name)) return;

            List<JavaIndexService.VariableInfo> vars = new ArrayList<>();
            sourceIndex.variables.forEach((k, v) -> { if (k.endsWith("#" + name)) vars.addAll(v); });
            if (vars.isEmpty()) {
                targetIndex.variables.forEach((k, v) -> { if (k.endsWith("#" + name)) vars.addAll(v); });
            }
            if (vars.isEmpty()) return;
            seen.add(name);

            JavaIndexService.VariableInfo vi = vars.get(0);
            // Determine which repo dir to use based on which index owns the variable
            boolean inSource = sourceIndex.variables.entrySet().stream()
                    .anyMatch(e -> e.getKey().endsWith("#" + name) && !e.getValue().isEmpty());
            Path repoDir = inSource ? sourceRepoDir : targetRepoDir;

            // Read the real declaration line from the file
            List<String> lineContent = readLines(repoDir, vi.filePath(),
                    vi.declarationLine(), vi.declarationLine());
            if (lineContent.isEmpty()) {
                // Fallback: at least show file+line so the reviewer can navigate
                lineContent = List.of(vi.filePath() + ":" + vi.declarationLine() + "  (type: " + vi.type() + ")");
            }

            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION,
                    vi.filePath(), vi.declarationLine(), vi.declarationLine(), vi.name(),
                    lineContent,
                    "Declaration of local variable '" + vi.name() + "' (type: " + vi.type() + ") used in changed lines"
            ));
        });
    }

    /**
     * Extracts all Java-identifier tokens from the text of non-CONTEXT changed lines.
     * Used as a relevance gate for variable-declaration enrichment.
     */
    private Set<String> extractIdentifiers(List<ChangedLine> lines) {
        Set<String> ids = new HashSet<>();
        for (ChangedLine l : lines) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            String text = l.text();
            if (text == null || text.isBlank()) continue;
            // Split on anything that is not a Java identifier character
            for (String token : text.split("[^\\w]+")) {
                if (!token.isBlank() && Character.isLetter(token.charAt(0))) {
                    ids.add(token);
                }
            }
        }
        return ids;
    }

    // -----------------------------------------------------------------------
    // Strategy 4: METHOD_CALLERS when method declaration was changed
    // -----------------------------------------------------------------------

    private void strategyChangedMethodDeclaration(
            ChangeGroup group, List<ChangedLine> added,
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
            List<JavaIndexService.CallSite> callers =
                    indexService.findCallSites(sourceIndex, method.qualifiedKey());
            if (callers.isEmpty()) continue;
            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.METHOD_CALLERS,
                    method.filePath(), method.startLine(), method.endLine(), method.name(),
                    callers.stream().limit(10)
                            .map(cs -> cs.filePath() + ":" + cs.line() +
                                    "  " + cs.methodName() +
                                    "(" + String.join(", ", cs.argumentTexts()) + ")")
                            .toList(),
                    "All callers of changed method '" + method.signature() + "'"
            ));
            addMethodBodySnippet(method, sourceRepoDir, snippets);
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 5: FIELD_USAGES / VARIABLE_USAGES for deleted declarations
    // -----------------------------------------------------------------------

    private void strategyDeletedDeclaration(
            List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex targetIndex,
            List<EnrichmentSnippet> snippets
    ) {
        for (ChangedLine line : deleted) {
            if (full(snippets)) break;
            int oldLine = line.oldLineNumber();
            if (oldLine <= 0) continue;
            String file = line.filePath();

            targetIndex.fieldsByName.values().stream()
                    .flatMap(Collection::stream)
                    .filter(f -> f.filePath().equals(file) && f.declarationLine() == oldLine)
                    .findFirst()
                    .ifPresent(f -> {
                        List<JavaIndexService.FieldAccess> accesses =
                                indexService.findFieldAccessesByName(targetIndex, f.name());
                        if (!accesses.isEmpty()) {
                            snippets.add(new EnrichmentSnippet(
                                    EnrichmentSnippet.SnippetType.FIELD_USAGES,
                                    f.filePath(), f.declarationLine(), f.declarationLine(), f.name(),
                                    accesses.stream().limit(10)
                                            .map(a -> a.filePath() + ":" + a.line()).toList(),
                                    "Field '" + f.name() + "' is deleted but still accessed in target"
                            ));
                        }
                    });

            targetIndex.variables.forEach((key, vars) ->
                    vars.stream()
                            .filter(v -> v.filePath().equals(file) && v.declarationLine() == oldLine)
                            .findFirst()
                            .ifPresent(v -> {
                                List<JavaIndexService.NameUsage> usages =
                                        indexService.findUsagesByName(targetIndex, v.name());
                                if (usages.size() > 1) {
                                    snippets.add(new EnrichmentSnippet(
                                            EnrichmentSnippet.SnippetType.VARIABLE_USAGES,
                                            v.filePath(), v.declarationLine(), v.declarationLine(), v.name(),
                                            usages.stream().limit(10)
                                                    .map(u -> u.filePath() + ":" + u.line()).toList(),
                                            "Variable '" + v.name() + "' is deleted but still used in target"
                                    ));
                                }
                            })
            );
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 6: ARGUMENT_CONTEXT when call arguments changed
    // -----------------------------------------------------------------------

    private void strategyChangedArguments(
            List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex,
            List<EnrichmentSnippet> snippets
    ) {
        Set<Integer> addedLines   = lineNumberSet(added);
        Set<Integer> deletedLines = lineNumberSet(deleted);

        Set<String> shownKeys = new HashSet<>();
        sourceIndex.callSites.forEach((calleeKey, sites) -> {
            if (full(snippets)) return;
            boolean inAdded   = sites.stream().anyMatch(cs -> addedLines.contains(cs.line()));
            boolean inDeleted = sites.stream().anyMatch(cs -> deletedLines.contains(cs.line()));
            if (!inAdded || !inDeleted) return;
            if (shownKeys.contains(calleeKey)) return;
            shownKeys.add(calleeKey);

            JavaIndexService.MethodInfo decl = sourceIndex.methods.get(calleeKey);
            if (decl == null) return;

            List<String> content = new ArrayList<>();
            content.add(decl.signature() + "  // parameters:");
            decl.parameters().forEach(p -> content.add("    " + p));
            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.ARGUMENT_CONTEXT,
                    decl.filePath(), decl.startLine(), decl.startLine(), decl.name(),
                    content,
                    "Arguments changed for '" + decl.name() + "' – verify new args match param types"
            ));
        });
    }

    // -----------------------------------------------------------------------
    // Strategy 7: METHOD_BODY – containing method as baseline context
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
                .ifPresent(m -> addMethodBodySnippet(m, sourceRepoDir, snippets));
    }

    // -----------------------------------------------------------------------
    // Snippet builders
    // -----------------------------------------------------------------------

    private void addMethodBodySnippet(
            JavaIndexService.MethodInfo m, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        int end = Math.min(m.endLine(), m.startLine() + maxSnippetLines - 1);
        List<String> lines = readLines(repoDir, m.filePath(), m.startLine(), end);
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_BODY,
                m.filePath(), m.startLine(), end, m.name(),
                lines, "Body of method '" + m.signature() + "'"
        ));
    }

    // -----------------------------------------------------------------------
    // File reading
    // -----------------------------------------------------------------------

    private List<String> readLines(Path repoDir, String relPath, int from, int to) {
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

    private Set<Integer> changedLineNumbers(List<ChangedLine> lines) {
        Set<Integer> set = new HashSet<>();
        for (ChangedLine l : lines) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.lineNumber()    > 0) set.add(l.lineNumber());
            if (l.oldLineNumber() > 0) set.add(l.oldLineNumber());
        }
        return set;
    }

    private Set<String> changedFiles(List<ChangedLine> lines) {
        Set<String> files = new HashSet<>();
        for (ChangedLine l : lines) {
            if (l.type() != ChangedLine.LineType.CONTEXT) files.add(l.filePath());
        }
        return files;
    }

    private Set<Integer> lineNumberSet(List<ChangedLine> lines) {
        Set<Integer> set = new HashSet<>();
        for (ChangedLine l : lines) {
            if (l.lineNumber()    > 0) set.add(l.lineNumber());
            if (l.oldLineNumber() > 0) set.add(l.oldLineNumber());
        }
        return set;
    }

    private int minLine(List<ChangedLine> lines, ToIntFunction<ChangedLine> fn) {
        return lines.stream().mapToInt(fn).filter(n -> n > 0).min().orElse(0);
    }

    private int maxLine(List<ChangedLine> lines, ToIntFunction<ChangedLine> fn) {
        return lines.stream().mapToInt(fn).filter(n -> n > 0).max().orElse(0);
    }

    private boolean full(List<EnrichmentSnippet> snippets) {
        return snippets.size() >= maxSnippetsPerGroup;
    }

    private void trim(List<EnrichmentSnippet> snippets) {
        while (snippets.size() > maxSnippetsPerGroup) snippets.remove(snippets.size() - 1);
    }
}
