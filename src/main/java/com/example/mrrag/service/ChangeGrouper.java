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
 *   <li><b>Code-block grouping (intra-file)</b> – changed lines inside the same method
 *       or lambda body are grouped together; lines outside any method body each form
 *       their own group.  Uses the AST index rather than arbitrary line-distance
 *       heuristics.  Before grouping, purely structural lines (lone braces, commented-out
 *       structural lines) are dropped, and mirror pairs
 *       (DELETE x / ADD {@code // x}) are merged into a single group so that
 *       "commenting-out" appears as one logical change.</li>
 *   <li><b>Cross-file AST merge</b> – groups from different files are merged when they
 *       share resolved symbol references (method declared in A called in B, etc.)
 *       via Union-Find on qualified keys.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private final AtomicInteger groupCounter = new AtomicInteger(0);

    /**
     * Matches lines that are purely structural: optional leading comment prefix
     * ({@code //}) followed only by braces/parens/brackets and/or the keywords
     * {@code try catch finally else} (with optional surrounding braces/spaces).
     *
     * <p>The pattern is applied after stripping the {@code //} prefix and all
     * surrounding whitespace, so it handles cases like
     * {@code "//        } finally {"} correctly.
     */
    // Matches the content AFTER comment-prefix and whitespace has been stripped
    private static final Pattern STRUCTURAL_CONTENT = Pattern.compile(
            "^[\\{\\}\\(\\)\\[\\]\\s]*"
            + "(?:(?:try|catch(?:\\s*\\([^)]*\\))?|finally|else(?:\\s+if\\s*\\([^)]*\\))?)"
            + "[\\{\\}\\(\\)\\[\\]\\s]*)?"
            + "$"
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public List<ChangeGroup> group(List<ChangedLine> lines) {
        return group(lines, null);
    }

    public List<ChangeGroup> group(List<ChangedLine> lines,
                                   JavaIndexService.ProjectIndex index) {
        // Step 1: drop structural-only lines (lone braces, try/finally, etc.)
        List<ChangedLine> meaningful = lines.stream()
                .filter(l -> l.type() == ChangedLine.LineType.CONTEXT || !isStructural(l.text()))
                .toList();
        int dropped = lines.size() - meaningful.size();
        if (dropped > 0) log.debug("Structural-line filter: dropped {}", dropped);

        // Step 2: merge mirror pairs (DELETE x  +  ADD "// x") before code-block grouping
        List<ChangedLine> merged = mergeMirrorCommentPairs(meaningful);
        int mirrorMerged = meaningful.size() - merged.size();
        if (mirrorMerged > 0) log.debug("Mirror-comment merge: collapsed {} lines into pairs", mirrorMerged);

        List<ChangeGroup> phase1 = codeBlockGroup(merged, index);
        log.debug("Phase 1 (code-block): {} groups", phase1.size());
        List<ChangeGroup> phase2 = semanticMerge(phase1, index);
        log.debug("Phase 2 (cross-file AST): {} groups", phase2.size());
        return phase2;
    }

    // -----------------------------------------------------------------------
    // Structural-line detection
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the line carries no semantic information and
     * should be suppressed from grouping and enrichment.
     *
     * <p>Logic:
     * <ol>
     *   <li>Strip leading/trailing whitespace.</li>
     *   <li>If the line starts with {@code //}, strip the comment prefix
     *       (and any further whitespace) to get the "content".</li>
     *   <li>Test the content against {@link #STRUCTURAL_CONTENT}.</li>
     * </ol>
     */
    static boolean isStructural(String text) {
        if (text == null) return true;
        String stripped = text.strip();
        if (stripped.isEmpty()) return true;
        String content = stripped.startsWith("//") ? stripped.substring(2).strip() : stripped;
        return STRUCTURAL_CONTENT.matcher(content).matches();
    }

    // -----------------------------------------------------------------------
    // Mirror-comment pair merging
    //
    // A "mirror pair" is: DELETE line with text T  +  ADD line with text "// T"
    // (or vice-versa: ADD "// T" before DELETE T is also accepted).
    // Such pairs result from the common "comment-out" refactoring and should
    // appear as a single logical change rather than two independent groups.
    //
    // Algorithm: single-pass scan within each file.  On a DELETE, look ahead
    // for an ADD whose uncommented text matches.  When found, emit a synthetic
    // CONTEXT line for the DELETE and keep the ADD (so the pair ends up in the
    // same bucket during code-block grouping).
    // -----------------------------------------------------------------------

    private List<ChangedLine> mergeMirrorCommentPairs(List<ChangedLine> lines) {
        // Group by file to avoid cross-file false positives
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);

        List<ChangedLine> result = new ArrayList<>();
        for (List<ChangedLine> fileLines : byFile.values()) {
            result.addAll(mergeMirrorInFile(fileLines));
        }
        return result;
    }

    private List<ChangedLine> mergeMirrorInFile(List<ChangedLine> lines) {
        int n = lines.size();
        boolean[] consumed = new boolean[n];
        List<ChangedLine> out = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            ChangedLine del = lines.get(i);
            if (del.type() != ChangedLine.LineType.DELETE) {
                out.add(del);
                continue;
            }
            // Look ahead up to 3 positions for a matching ADD
            String delNorm = normaliseForMirror(del.text());
            boolean paired = false;
            for (int j = i + 1; j < Math.min(n, i + 4); j++) {
                if (consumed[j]) continue;
                ChangedLine add = lines.get(j);
                if (add.type() != ChangedLine.LineType.ADD) continue;
                String addNorm = normaliseForMirror(uncomment(add.text()));
                if (delNorm.equals(addNorm) && !delNorm.isBlank()) {
                    // Merge: keep ADD (it has the new content), drop DELETE
                    consumed[j] = true;
                    consumed[i] = true;
                    // Emit DELETE first (as context so same bucket), then ADD
                    out.add(del.asContext());
                    out.add(add);
                    paired = true;
                    log.trace("Mirror pair merged: '{}'", delNorm);
                    break;
                }
            }
            if (!paired) out.add(del);
        }
        return out;
    }

    /** Strips the leading {@code //} comment marker and surrounding whitespace. */
    private static String uncomment(String text) {
        if (text == null) return "";
        String s = text.strip();
        if (s.startsWith("//")) return s.substring(2).strip();
        return s;
    }

    /** Normalises a line for mirror-pair comparison: strip, remove comment prefix, lowercase. */
    private static String normaliseForMirror(String text) {
        if (text == null) return "";
        return uncomment(text).toLowerCase(java.util.Locale.ROOT);
    }

    // -----------------------------------------------------------------------
    // Phase 1: code-block grouping within each file
    // -----------------------------------------------------------------------

    private List<ChangeGroup> codeBlockGroup(List<ChangedLine> lines,
                                              JavaIndexService.ProjectIndex index) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);

        List<ChangeGroup> result = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            result.addAll(groupByCodeBlock(entry.getKey(), entry.getValue(), index));
        }
        return result;
    }

    private List<ChangeGroup> groupByCodeBlock(String file,
                                                List<ChangedLine> lines,
                                                JavaIndexService.ProjectIndex index) {
        Map<Integer, List<ChangedLine>> buckets = new LinkedHashMap<>();
        int outOfMethodCounter = -1;

        for (ChangedLine line : lines) {
            int lineNo = effectiveLine(line);
            Integer bucketKey;

            if (index != null && lineNo > 0) {
                Optional<JavaIndexService.MethodInfo> container = findContainer(index, file, lineNo);
                bucketKey = container.map(JavaIndexService.MethodInfo::startLine)
                        .orElse(outOfMethodCounter--);
            } else {
                bucketKey = outOfMethodCounter--;
            }

            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(line);
        }

        List<ChangeGroup> groups = new ArrayList<>();
        for (List<ChangedLine> bucket : buckets.values()) {
            if (bucket.stream().allMatch(l -> l.type() == ChangedLine.LineType.CONTEXT)) continue;
            String id = "G" + groupCounter.incrementAndGet();
            groups.add(new ChangeGroup(id, file, bucket, new ArrayList<>()));
        }
        return groups;
    }

    private Optional<JavaIndexService.MethodInfo> findContainer(
            JavaIndexService.ProjectIndex index, String file, int lineNo) {
        List<JavaIndexService.MethodInfo> methods =
                index.methodsByFile.getOrDefault(file, List.of());
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

        index.methodsByFile.getOrDefault(file, List.of()).stream()
                .filter(m -> changedLines.contains(m.startLine()))
                .map(JavaIndexService.MethodInfo::qualifiedKey).forEach(result::add);

        index.callSites.values().stream().flatMap(Collection::stream)
                .filter(cs -> cs.filePath().equals(file) && cs.resolvedKey() != null
                        && changedLines.contains(cs.line()))
                .map(JavaIndexService.CallSite::resolvedKey).forEach(result::add);

        index.fieldsByName.values().stream().flatMap(Collection::stream)
                .filter(f -> f.filePath().equals(file) && changedLines.contains(f.declarationLine()))
                .map(JavaIndexService.FieldInfo::resolvedKey).forEach(result::add);

        index.fieldAccesses.values().stream().flatMap(Collection::stream)
                .filter(fa -> fa.filePath().equals(file) && fa.resolvedKey() != null
                        && changedLines.contains(fa.line()))
                .map(JavaIndexService.FieldAccess::resolvedKey).forEach(result::add);

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
