package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Two-phase grouping:
 * <ol>
 *   <li><b>Code-block grouping (intra-file)</b> – within each file, changed lines that
 *       fall inside the same method or lambda body are grouped together. Changed lines
 *       outside any method body (field declarations, class-level annotations, etc.) each
 *       form their own single-line group. This uses the AST index rather than arbitrary
 *       line-distance heuristics.</li>
 *   <li><b>Cross-file AST merge</b> – groups from different files are merged when they
 *       are linked by actual resolved symbol references:
 *       <ul>
 *         <li>Group A declares method M → Group B calls M</li>
 *         <li>Group A declares field F  → Group B accesses F</li>
 *       </ul>
 *       Uses resolved qualified keys from JavaParser SymbolSolver via Union-Find.
 *   </li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private final AtomicInteger groupCounter = new AtomicInteger(0);

    /**
     * Lines that carry no semantic meaning on their own and should not form
     * independent groups or pollute context enrichment.
     *
     * <p>A line is structural if, after stripping leading whitespace and optional
     * comment prefixes ({@code //}), it matches only braces/brackets, keywords like
     * {@code try/catch/finally/else}, or is empty.
     */
    private static final Pattern STRUCTURAL_LINE = Pattern.compile(
            "^(?://+\\s*)?[\\{\\}\\(\\)\\[\\]]*\\s*" +
            "(?:try|catch|finally|else|\\}\\s*else|\\}\\s*catch|\\}\\s*finally)?" +
            "[\\{\\}\\(\\)\\[\\]]*\\s*$"
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Grouping without an AST index (fallback: each file's changes = one group).
     */
    public List<ChangeGroup> group(List<ChangedLine> lines) {
        return group(lines, null);
    }

    /**
     * Full grouping: code-block (intra-file) + cross-file AST merge.
     *
     * @param lines       all changed lines from the diff
     * @param index       resolved AST index of the source branch (may be null)
     * @return cohesive ChangeGroups
     */
    public List<ChangeGroup> group(List<ChangedLine> lines,
                                   JavaIndexService.ProjectIndex index) {
        // Remove pure structural changes before grouping so that a deleted `}` or
        // `try {` does not become its own group and pollute cross-file AST merge.
        List<ChangedLine> meaningful = lines.stream()
                .filter(l -> l.type() == ChangedLine.LineType.CONTEXT || !isStructural(l.text()))
                .toList();

        int dropped = lines.size() - meaningful.size();
        if (dropped > 0) {
            log.debug("Structural-line filter: dropped {} lines (e.g. lone braces, try/finally)", dropped);
        }

        List<ChangeGroup> phase1 = codeBlockGroup(meaningful, index);
        log.debug("Phase 1 (code-block): {} groups", phase1.size());
        List<ChangeGroup> phase2 = semanticMerge(phase1, index);
        log.debug("Phase 2 (cross-file AST): {} groups", phase2.size());
        return phase2;
    }

    // -----------------------------------------------------------------------
    // Structural-line detection
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the line text is purely structural (brace/bracket,
     * {@code try}, {@code catch}, {@code finally}, {@code else}, or empty/commented-out
     * equivalent) and carries no semantic information for the reviewer.
     */
    static boolean isStructural(String text) {
        if (text == null) return true;
        String stripped = text.strip();
        if (stripped.isEmpty()) return true;
        // Commented-out lines that are themselves structural (e.g. `//        try {`)
        String withoutComment = stripped.startsWith("//") ? stripped.substring(2).strip() : stripped;
        return STRUCTURAL_LINE.matcher(withoutComment).matches();
    }

    // -----------------------------------------------------------------------
    // Phase 1: code-block grouping within each file
    // -----------------------------------------------------------------------

    private List<ChangeGroup> codeBlockGroup(List<ChangedLine> lines,
                                              JavaIndexService.ProjectIndex index) {
        // Partition by file first
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) {
            byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        }

        List<ChangeGroup> result = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            result.addAll(groupByCodeBlock(entry.getKey(), entry.getValue(), index));
        }
        return result;
    }

    /**
     * For a single file: map each changed line to the enclosing method/lambda body
     * (identified by its start line in the AST index), then group by that key.
     * Lines that fall outside any method body get their own individual group.
     */
    private List<ChangeGroup> groupByCodeBlock(String file,
                                                List<ChangedLine> lines,
                                                JavaIndexService.ProjectIndex index) {
        // bucket key: Integer (method startLine) for in-method lines,
        //             negative unique sentinel for out-of-method lines
        Map<Integer, List<ChangedLine>> buckets = new LinkedHashMap<>();
        int outOfMethodCounter = -1;

        for (ChangedLine line : lines) {
            int lineNo = effectiveLine(line);
            Integer bucketKey;

            if (index != null && lineNo > 0) {
                Optional<JavaIndexService.MethodInfo> container =
                        indexService(index, file, lineNo);
                bucketKey = container.map(JavaIndexService.MethodInfo::startLine)
                        .orElse(outOfMethodCounter--);
            } else {
                // No index available: each changed line is its own group
                bucketKey = outOfMethodCounter--;
            }

            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(line);
        }

        List<ChangeGroup> groups = new ArrayList<>();
        for (List<ChangedLine> bucket : buckets.values()) {
            // Skip buckets that contain only CONTEXT lines (shouldn't happen but guard)
            if (bucket.stream().allMatch(l -> l.type() == ChangedLine.LineType.CONTEXT)) continue;
            String id = "G" + groupCounter.incrementAndGet();
            groups.add(new ChangeGroup(id, file, bucket, new ArrayList<>()));
        }
        return groups;
    }

    /**
     * Finds the innermost method/lambda in the index that contains {@code lineNo}.
     * Checks both ADD-side ({@code lineNumber}) and DELETE-side ({@code oldLineNumber})
     * line numbers so that deleted lines are also correctly bucketed.
     */
    private Optional<JavaIndexService.MethodInfo> indexService(
            JavaIndexService.ProjectIndex index, String file, int lineNo) {
        List<JavaIndexService.MethodInfo> methods =
                index.methodsByFile.getOrDefault(file, List.of());
        // Pick the innermost (smallest span) method that contains the line
        return methods.stream()
                .filter(m -> m.startLine() <= lineNo && m.endLine() >= lineNo)
                .min(Comparator.comparingInt(m -> m.endLine() - m.startLine()));
    }

    private int effectiveLine(ChangedLine l) {
        return l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
    }

    // -----------------------------------------------------------------------
    // Phase 2: cross-file AST merge via Union-Find
    // -----------------------------------------------------------------------

    private List<ChangeGroup> semanticMerge(List<ChangeGroup> groups,
                                             JavaIndexService.ProjectIndex index) {
        if (groups.size() <= 1 || index == null) return groups;

        int n = groups.size();
        List<Set<String>> keys = new ArrayList<>();
        for (ChangeGroup g : groups) keys.add(buildAstKeys(g, index));

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                if (!Collections.disjoint(keys.get(i), keys.get(j))) union(parent, i, j);
            }
        }

        Map<Integer, List<ChangeGroup>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }

        List<ChangeGroup> result = new ArrayList<>();
        for (List<ChangeGroup> cluster : clusters.values()) {
            if (cluster.size() == 1) {
                result.add(cluster.get(0));
            } else {
                log.debug("Cross-file merge: {} groups -> one (files: {})",
                        cluster.size(), cluster.stream().map(ChangeGroup::primaryFile).toList());
                result.add(mergeCluster(cluster));
            }
        }
        return result;
    }

    private Set<String> buildAstKeys(ChangeGroup group, JavaIndexService.ProjectIndex index) {
        Set<String> result = new HashSet<>();
        if (index == null) return result;

        String file = group.primaryFile();
        Set<Integer> changedLines = new HashSet<>();
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.lineNumber()    > 0) changedLines.add(l.lineNumber());
            if (l.oldLineNumber() > 0) changedLines.add(l.oldLineNumber());
        }
        if (changedLines.isEmpty()) return result;

        // Methods declared in changed lines
        index.methodsByFile.getOrDefault(file, List.of()).stream()
                .filter(m -> changedLines.contains(m.startLine()))
                .map(JavaIndexService.MethodInfo::qualifiedKey)
                .forEach(result::add);

        // Methods called from changed lines (resolved)
        index.callSites.values().stream()
                .flatMap(Collection::stream)
                .filter(cs -> cs.filePath().equals(file)
                        && cs.resolvedKey() != null
                        && changedLines.contains(cs.line()))
                .map(JavaIndexService.CallSite::resolvedKey)
                .forEach(result::add);

        // Fields declared in changed lines
        index.fieldsByName.values().stream()
                .flatMap(Collection::stream)
                .filter(f -> f.filePath().equals(file)
                        && changedLines.contains(f.declarationLine()))
                .map(JavaIndexService.FieldInfo::resolvedKey)
                .forEach(result::add);

        // Fields accessed from changed lines
        index.fieldAccesses.values().stream()
                .flatMap(Collection::stream)
                .filter(fa -> fa.filePath().equals(file)
                        && fa.resolvedKey() != null
                        && changedLines.contains(fa.line()))
                .map(JavaIndexService.FieldAccess::resolvedKey)
                .forEach(result::add);

        return result;
    }

    private ChangeGroup mergeCluster(List<ChangeGroup> cluster) {
        String id = "G" + groupCounter.incrementAndGet();
        String primary = cluster.stream()
                .max(Comparator.comparingLong(g -> g.changedLines().stream()
                        .filter(l -> l.type() != ChangedLine.LineType.CONTEXT).count()))
                .map(ChangeGroup::primaryFile)
                .orElse(cluster.get(0).primaryFile());
        List<ChangedLine> allLines = new ArrayList<>();
        for (ChangeGroup g : cluster) allLines.addAll(g.changedLines());
        return new ChangeGroup(id, primary, allLines, new ArrayList<>());
    }

    // -----------------------------------------------------------------------
    // Union-Find
    // -----------------------------------------------------------------------

    private int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
