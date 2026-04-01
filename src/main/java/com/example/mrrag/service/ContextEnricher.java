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
 *   <li><b>METHOD_DECLARATION</b>  – declaration (signature up to opening brace) of every
 *       method called on changed lines, read from the actual source file.</li>
 *   <li><b>FIELD_DECLARATION</b>   – declaration line of every field accessed on changed lines.</li>
 *   <li><b>VARIABLE_DECLARATION</b>– declaration line of every local variable whose simple name
 *       appears in the changed-line text AND is not a Java keyword or annotation attribute.
 *       Read from the actual source file.</li>
 *   <li><b>METHOD_CALLERS</b>      – if a method declaration was changed, list all call sites.</li>
 *   <li><b>FIELD_USAGES</b>        – if a field declaration was deleted, list all accesses.</li>
 *   <li><b>VARIABLE_USAGES</b>     – if a variable declaration was deleted, list all usages.</li>
 *   <li><b>ARGUMENT_CONTEXT</b>    – if a method is called with changed arguments, show the
 *       actual declaration signature read from the source file (no synthetic formatting).
 *       Only emitted when the argument count/text actually differs between ADD and DELETE.</li>
 *   <li><b>METHOD_BODY</b>         – baseline: body of the method containing the change.</li>
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

    /**
     * Java keywords and common annotation-attribute names that must never be
     * surfaced as VARIABLE_DECLARATION enrichments even if a local variable
     * with the same simple name exists somewhere in the codebase.
     */
    private static final Set<String> KEYWORD_BLACKLIST = Set.of(
            // Java reserved words
            "abstract","assert","boolean","break","byte","case","catch","char",
            "class","const","continue","default","do","double","else","enum",
            "extends","final","finally","float","for","goto","if","implements",
            "import","instanceof","int","interface","long","native","new","null",
            "package","private","protected","public","return","short","static",
            "strictfp","super","switch","synchronized","this","throw","throws",
            "transient","try","void","volatile","while","true","false","var",
            "record","sealed","permits","yield",
            // Common annotation attribute names that look like identifiers
            "value","name","type","method","path","required","defaultValue",
            "produces","consumes","headers","params"
    );

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

        strategyMethodDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);
        strategyFieldDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);
        strategyVariableDeclarations(all, sourceIndex, targetIndex, sourceRepoDir, targetRepoDir, snippets);
        strategyChangedMethodDeclaration(group, added, sourceIndex, sourceRepoDir, snippets);
        strategyDeletedDeclaration(deleted, targetIndex, snippets);
        strategyChangedArguments(added, deleted, sourceIndex, sourceRepoDir, snippets);
        strategyContainingMethod(group, sourceIndex, sourceRepoDir, snippets);

        trim(snippets);
    }

    // -----------------------------------------------------------------------
    // Strategy 1: METHOD_DECLARATION
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
        Set<String> changedFiles = changedFiles(all);

        sourceIndex.callSites.forEach((calleeKey, sites) -> {
            if (full(snippets)) return;
            boolean onChangedLine = sites.stream()
                    .anyMatch(cs -> changedFiles.contains(cs.filePath()) && changedLineNos.contains(cs.line()));
            if (!onChangedLine || seen.contains(calleeKey)) return;
            seen.add(calleeKey);

            JavaIndexService.MethodInfo decl =
                    sourceIndex.methods.getOrDefault(calleeKey, targetIndex.methods.get(calleeKey));
            if (decl == null) return;
            Path repoDir = sourceIndex.methods.containsKey(calleeKey) ? sourceRepoDir : targetRepoDir;
            addMethodDeclarationSnippet(decl, repoDir, snippets);
        });
    }

    private void addMethodDeclarationSnippet(
            JavaIndexService.MethodInfo m, Path repoDir,
            List<EnrichmentSnippet> snippets
    ) {
        if (full(snippets)) return;
        List<String> declLines = readUntilOpenBrace(repoDir, m.filePath(), m.startLine(), m.endLine());
        if (declLines.isEmpty()) return;
        int signatureEnd = m.startLine() + declLines.size() - 1;
        snippets.add(new EnrichmentSnippet(
                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                m.filePath(), m.startLine(), signatureEnd, m.name(),
                declLines,
                "Declaration of method '" + m.name() + "' used in changed lines"
        ));
    }

    // -----------------------------------------------------------------------
    // Strategy 2: FIELD_DECLARATION
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
            if (!onChangedLine || seen.contains(fieldKey)) return;
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
    // Strategy 3: VARIABLE_DECLARATION
    //
    // Only surface a variable when its simple name:
    //  (a) literally appears in the text of a non-CONTEXT changed line,
    //  (b) is NOT in the keyword/annotation-attribute blacklist,
    //  (c) appears as a whole word (not a substring of a longer identifier),
    //  (d) the variable's declaring file is one of the changed files OR the
    //      declaration is in the same package (heuristic: same project root).
    // -----------------------------------------------------------------------

    private void strategyVariableDeclarations(
            List<ChangedLine> all,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        Set<String> namesInText = extractRelevantIdentifiers(all);
        Set<String> changedFiles = changedFiles(all);
        Set<String> seen = new HashSet<>();

        sourceIndex.nameUsagesBySimpleName.forEach((name, usages) -> {
            if (full(snippets)) return;
            if (!namesInText.contains(name)) return;
            if (seen.contains(name)) return;

            // Collect candidate variable infos
            List<JavaIndexService.VariableInfo> vars = new ArrayList<>();
            sourceIndex.variables.forEach((k, v) -> { if (k.endsWith("#" + name)) vars.addAll(v); });
            if (vars.isEmpty()) {
                targetIndex.variables.forEach((k, v) -> { if (k.endsWith("#" + name)) vars.addAll(v); });
            }
            if (vars.isEmpty()) return;

            // Prefer a variable declared in one of the changed files
            JavaIndexService.VariableInfo vi = vars.stream()
                    .filter(v -> changedFiles.contains(v.filePath()))
                    .findFirst()
                    .orElse(vars.get(0));

            // Skip if the declaring file is completely outside the changed files
            // and the name is very common (length < 4) – avoids noise like 's', 'i'
            if (!changedFiles.contains(vi.filePath()) && name.length() < 4) return;

            seen.add(name);

            boolean inSource = sourceIndex.variables.entrySet().stream()
                    .anyMatch(e -> e.getKey().endsWith("#" + name) && !e.getValue().isEmpty());
            Path repoDir = inSource ? sourceRepoDir : targetRepoDir;

            List<String> lineContent = readLines(repoDir, vi.filePath(),
                    vi.declarationLine(), vi.declarationLine());
            if (lineContent.isEmpty()) {
                lineContent = List.of(vi.filePath() + ":" + vi.declarationLine()
                        + "  // type: " + vi.type());
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
     * Extracts Java-identifier tokens from non-CONTEXT changed lines,
     * excluding Java keywords and known annotation-attribute names.
     * Also strips comment prefixes ({@code //}) before tokenising so that
     * commented-out code contributes its real identifiers.
     */
    private Set<String> extractRelevantIdentifiers(List<ChangedLine> lines) {
        Set<String> ids = new HashSet<>();
        for (ChangedLine l : lines) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            String text = l.text();
            if (text == null || text.isBlank()) continue;
            // Strip comment prefix so `//   hooks.add(x)` contributes `hooks`, `add`, `x`
            String stripped = text.strip();
            if (stripped.startsWith("//")) stripped = stripped.substring(2).strip();
            for (String token : stripped.split("[^\\w]+")) {
                if (!token.isBlank()
                        && Character.isLetter(token.charAt(0))
                        && !KEYWORD_BLACKLIST.contains(token)) {
                    ids.add(token);
                }
            }
        }
        return ids;
    }

    // -----------------------------------------------------------------------
    // Strategy 4: METHOD_CALLERS
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
                            .map(cs -> cs.filePath() + ":" + cs.line()
                                    + "  " + cs.methodName()
                                    + "(" + String.join(", ", cs.argumentTexts()) + ")")
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
    // Strategy 6: ARGUMENT_CONTEXT
    //
    // Triggered only when the same callee key appears in BOTH ADD and DELETE
    // lines AND the argument texts actually differ (not just line-number churn).
    // Content is read directly from the source file (real declaration lines)
    // instead of being assembled synthetically.
    // -----------------------------------------------------------------------

    private void strategyChangedArguments(
            List<ChangedLine> added, List<ChangedLine> deleted,
            JavaIndexService.ProjectIndex sourceIndex,
            Path sourceRepoDir,
            List<EnrichmentSnippet> snippets
    ) {
        // Build maps: calleeKey -> set of argument-text fingerprints per side
        Map<String, Set<String>> addedArgs   = collectArgFingerprints(added, sourceIndex);
        Map<String, Set<String>> deletedArgs = collectArgFingerprints(deleted, sourceIndex);

        Set<String> shownKeys = new HashSet<>();
        for (String calleeKey : addedArgs.keySet()) {
            if (full(snippets)) break;
            if (!deletedArgs.containsKey(calleeKey)) continue; // only in ADD side – new call, not changed
            if (shownKeys.contains(calleeKey)) continue;

            Set<String> aArgs = addedArgs.get(calleeKey);
            Set<String> dArgs = deletedArgs.get(calleeKey);
            if (aArgs.equals(dArgs)) continue; // arguments did not actually change

            shownKeys.add(calleeKey);

            JavaIndexService.MethodInfo decl = sourceIndex.methods.get(calleeKey);
            if (decl == null) continue;

            // Read the real declaration from file (signature up to opening brace)
            List<String> sigLines = readUntilOpenBrace(sourceRepoDir, decl.filePath(),
                    decl.startLine(), decl.endLine());
            if (sigLines.isEmpty()) continue;

            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.ARGUMENT_CONTEXT,
                    decl.filePath(), decl.startLine(),
                    decl.startLine() + sigLines.size() - 1, decl.name(),
                    sigLines,
                    "Arguments changed for '" + decl.name() + "' – verify new args match parameter types"
            ));
        }
    }

    /**
     * For each call site on one side of the diff, collect a fingerprint of the
     * argument texts so we can compare ADD-side vs DELETE-side arguments.
     */
    private Map<String, Set<String>> collectArgFingerprints(
            List<ChangedLine> lines, JavaIndexService.ProjectIndex index) {
        Set<Integer> lineNos = lineNumberSet(lines);
        Set<String> files = changedFiles(lines);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        index.callSites.forEach((calleeKey, sites) -> {
            for (JavaIndexService.CallSite cs : sites) {
                if (files.contains(cs.filePath()) && lineNos.contains(cs.line())) {
                    result.computeIfAbsent(calleeKey, k -> new LinkedHashSet<>())
                          .add(String.join(",", cs.argumentTexts()));
                }
            }
        });
        return result;
    }

    // -----------------------------------------------------------------------
    // Strategy 7: METHOD_BODY
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
    // File reading helpers
    // -----------------------------------------------------------------------

    /**
     * Reads lines from {@code startLine} (1-based, inclusive) until the first
     * line that contains {@code '{'}, inclusive.  Used to extract method signatures.
     */
    private List<String> readUntilOpenBrace(Path repoDir, String relPath, int startLine, int maxLine) {
        Path file = repoDir.resolve(relPath);
        if (!Files.exists(file)) return List.of();
        List<String> all;
        try { all = Files.readAllLines(file); }
        catch (IOException e) { log.warn("Cannot read {}: {}", file, e.getMessage()); return List.of(); }

        List<String> result = new ArrayList<>();
        for (int i = startLine - 1; i < Math.min(all.size(), maxLine); i++) {
            String line = all.get(i);
            result.add(line.length() > 200 ? line.substring(0, 200) + "..." : line);
            if (line.contains("{")) break;
        }
        return result;
    }

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
