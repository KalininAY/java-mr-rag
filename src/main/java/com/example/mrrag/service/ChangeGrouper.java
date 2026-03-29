package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Two-phase grouping:
 * <ol>
 *   <li><b>Proximity grouping</b> – within each file, hunks closer than
 *       {@code PROXIMITY_THRESHOLD} lines are merged.</li>
 *   <li><b>AST-based semantic grouping</b> – after the index is built,
 *       groups from different files are merged when they are linked by
 *       actual symbol references in the AST:
 *       <ul>
 *         <li>Group A declares a method M → Group B calls M ({@code callSites} index)</li>
 *         <li>Group A declares a field F  → Group B accesses F ({@code fieldAccesses} index)</li>
 *         <li>Group A adds/removes a method call → Group A's callee is declared in Group B</li>
 *       </ul>
 *       This is <em>not</em> textual keyword matching – it uses the resolved qualified
 *       keys from JavaParser SymbolSolver.
 *   </li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private static final int PROXIMITY_THRESHOLD = 20;

    private final AtomicInteger groupCounter = new AtomicInteger(0);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Phase-1 only grouping (without index). Used when index is not yet available.
     */
    public List<ChangeGroup> group(List<ChangedLine> lines) {
        return semanticMerge(proximityGroup(lines), null);
    }

    /**
     * Full grouping: proximity + AST-based cross-file merge.
     *
     * @param lines        all changed lines from the diff
     * @param sourceIndex  resolved AST index of the source branch
     * @return grouped ChangeGroups
     */
    public List<ChangeGroup> group(List<ChangedLine> lines,
                                   JavaIndexService.ProjectIndex sourceIndex) {
        List<ChangeGroup> phase1 = proximityGroup(lines);
        log.debug("Phase 1 (proximity): {} groups", phase1.size());
        List<ChangeGroup> phase2 = semanticMerge(phase1, sourceIndex);
        log.debug("Phase 2 (AST semantic merge): {} groups", phase2.size());
        return phase2;
    }

    // -----------------------------------------------------------------------
    // Phase 1: proximity grouping within each file
    // -----------------------------------------------------------------------

    private List<ChangeGroup> proximityGroup(List<ChangedLine> lines) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine line : lines) {
            byFile.computeIfAbsent(line.filePath(), k -> new ArrayList<>()).add(line);
        }
        List<ChangeGroup> result = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            result.addAll(groupFileLines(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private List<ChangeGroup> groupFileLines(String file, List<ChangedLine> lines) {
        List<List<ChangedLine>> hunks = splitIntoHunks(lines);
        List<List<ChangedLine>> merged = mergeNearbyHunks(hunks);
        List<ChangeGroup> groups = new ArrayList<>();
        for (List<ChangedLine> hunk : merged) {
            if (hunk.stream().anyMatch(l -> l.type() != ChangedLine.LineType.CONTEXT)) {
                String id = "G" + groupCounter.incrementAndGet();
                groups.add(new ChangeGroup(id, file, hunk, new ArrayList<>()));
            }
        }
        return groups;
    }

    private List<List<ChangedLine>> splitIntoHunks(List<ChangedLine> lines) {
        List<ChangedLine> current = new ArrayList<>();
        List<List<ChangedLine>> result = new ArrayList<>();
        for (ChangedLine line : lines) {
            if (line.type() != ChangedLine.LineType.CONTEXT) {
                current.add(line);
            } else if (!current.isEmpty()) {
                current.add(line);
            }
        }
        if (!current.isEmpty()) {
            result.addAll(splitAtContextGaps(current));
        }
        return result;
    }

    private List<List<ChangedLine>> splitAtContextGaps(List<ChangedLine> hunk) {
        List<List<ChangedLine>> result = new ArrayList<>();
        List<ChangedLine> current = new ArrayList<>();
        int contextRun = 0;
        for (ChangedLine line : hunk) {
            if (line.type() == ChangedLine.LineType.CONTEXT) {
                contextRun++;
                if (contextRun > PROXIMITY_THRESHOLD && !current.isEmpty()) {
                    result.add(current);
                    current = new ArrayList<>();
                    contextRun = 0;
                    continue;
                }
            } else {
                contextRun = 0;
            }
            current.add(line);
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private List<List<ChangedLine>> mergeNearbyHunks(List<List<ChangedLine>> hunks) {
        if (hunks.size() <= 1) return hunks;
        List<List<ChangedLine>> merged = new ArrayList<>();
        List<ChangedLine> current = new ArrayList<>(hunks.get(0));
        for (int i = 1; i < hunks.size(); i++) {
            List<ChangedLine> next = hunks.get(i);
            if (lineGap(current, next) <= PROXIMITY_THRESHOLD) {
                current.addAll(next);
            } else {
                merged.add(current);
                current = new ArrayList<>(next);
            }
        }
        merged.add(current);
        return merged;
    }

    // -----------------------------------------------------------------------
    // Phase 2: AST-based cross-file semantic merge
    // -----------------------------------------------------------------------

    /**
     * Build a set of <em>resolved qualified keys</em> for each group:
     * <ul>
     *   <li>Keys of methods <b>declared</b> in the group's changed lines</li>
     *   <li>Keys of methods <b>called</b> from the group's changed lines
     *       (looked up via {@code callSites} index – only resolved ones)</li>
     *   <li>Keys of fields <b>declared</b> in changed lines</li>
     *   <li>Keys of fields <b>accessed</b> from changed lines</li>
     * </ul>
     * Two groups that share at least one key are merged via Union-Find.
     */
    private List<ChangeGroup> semanticMerge(List<ChangeGroup> groups,
                                             JavaIndexService.ProjectIndex index) {
        if (groups.size() <= 1) return groups;

        int n = groups.size();
        List<Set<String>> keys = new ArrayList<>();
        for (ChangeGroup g : groups) {
            keys.add(buildAstKeys(g, index));
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                // Only merge cross-file groups
                if (groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                if (!Collections.disjoint(keys.get(i), keys.get(j))) {
                    union(parent, i, j);
                }
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
                result.add(mergeCluster(cluster));
            }
        }
        return result;
    }

    /**
     * Collect resolved AST keys that represent symbols declared or used
     * in the ADD/DELETE lines of a group.
     *
     * Without an index we fall back to an empty set (no cross-file merging).
     */
    private Set<String> buildAstKeys(ChangeGroup group, JavaIndexService.ProjectIndex index) {
        Set<String> result = new HashSet<>();
        if (index == null) return result;

        String file = group.primaryFile();
        Set<Integer> changedLineNumbers = new HashSet<>();
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.lineNumber() > 0)    changedLineNumbers.add(l.lineNumber());
            if (l.oldLineNumber() > 0) changedLineNumbers.add(l.oldLineNumber());
        }
        if (changedLineNumbers.isEmpty()) return result;

        int minLine = changedLineNumbers.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxLine = changedLineNumbers.stream().mapToInt(Integer::intValue).max().orElse(0);

        // --- Methods declared in changed lines ---
        index.methodsByFile.getOrDefault(file, List.of()).stream()
                .filter(m -> m.startLine() >= minLine && m.startLine() <= maxLine)
                .map(JavaIndexService.MethodInfo::qualifiedKey)
                .forEach(result::add);

        // --- Methods called from changed lines (resolved call sites) ---
        // For each call site in this file that falls on a changed line,
        // add the resolved callee key so the group containing the declaration is linked.
        index.callSites.values().stream()
                .flatMap(Collection::stream)
                .filter(cs -> cs.filePath().equals(file)
                        && cs.resolvedKey() != null
                        && changedLineNumbers.contains(cs.line()))
                .map(JavaIndexService.CallSite::resolvedKey)
                .forEach(result::add);

        // --- Fields declared in changed lines ---
        index.fieldsByName.values().stream()
                .flatMap(Collection::stream)
                .filter(f -> f.filePath().equals(file)
                        && changedLineNumbers.contains(f.declarationLine()))
                .map(JavaIndexService.FieldInfo::resolvedKey)
                .forEach(result::add);

        // --- Fields accessed from changed lines ---
        index.fieldAccesses.values().stream()
                .flatMap(Collection::stream)
                .filter(fa -> fa.filePath().equals(file)
                        && fa.resolvedKey() != null
                        && changedLineNumbers.contains(fa.line()))
                .map(JavaIndexService.FieldAccess::resolvedKey)
                .forEach(result::add);

        return result;
    }

    private ChangeGroup mergeCluster(List<ChangeGroup> cluster) {
        String id = "G" + groupCounter.incrementAndGet();
        String primary = cluster.stream()
                .max(Comparator.comparingLong(g ->
                        g.changedLines().stream()
                                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                                .count()))
                .map(ChangeGroup::primaryFile)
                .orElse(cluster.get(0).primaryFile());
        List<ChangedLine> allLines = new ArrayList<>();
        for (ChangeGroup g : cluster) allLines.addAll(g.changedLines());
        log.debug("AST semantic merge: {} groups -> {} (files: {})",
                cluster.size(), id,
                cluster.stream().map(ChangeGroup::primaryFile).toList());
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

    // -----------------------------------------------------------------------
    // Line number helpers
    // -----------------------------------------------------------------------

    private int lineGap(List<ChangedLine> a, List<ChangedLine> b) {
        return Math.abs(firstEffectiveLine(b) - lastEffectiveLine(a));
    }

    private int lastEffectiveLine(List<ChangedLine> hunk) {
        for (int i = hunk.size() - 1; i >= 0; i--) {
            ChangedLine l = hunk.get(i);
            if (l.lineNumber() > 0) return l.lineNumber();
            if (l.oldLineNumber() > 0) return l.oldLineNumber();
        }
        return 0;
    }

    private int firstEffectiveLine(List<ChangedLine> hunk) {
        for (ChangedLine l : hunk) {
            if (l.lineNumber() > 0) return l.lineNumber();
            if (l.oldLineNumber() > 0) return l.oldLineNumber();
        }
        return 0;
    }
}
