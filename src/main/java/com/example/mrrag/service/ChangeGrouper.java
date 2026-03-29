package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Two-phase grouping:
 * <ol>
 *   <li><b>Proximity grouping</b> – within each file, hunks closer than
 *       {@code PROXIMITY_THRESHOLD} lines are merged.</li>
 *   <li><b>Semantic grouping</b> – groups from different files are merged when
 *       they share a symbol name (method call, field, variable) that appears in
 *       both. This catches the classic pattern: method added in ClassA, called
 *       in ClassB – even though the changes are "far apart" in the diff.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private static final int PROXIMITY_THRESHOLD = 20;

    // Matches identifiers followed by '(' (method calls) or used as plain names
    private static final Pattern SYMBOL_PATTERN =
            Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]{2,})\\b");

    private final AtomicInteger groupCounter = new AtomicInteger(0);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Group changed lines into ChangeGroups, including cross-file semantic links.
     *
     * @param lines all changed lines (ADD, DELETE, CONTEXT) from the full MR diff
     * @return list of ChangeGroups with no enrichments yet
     */
    public List<ChangeGroup> group(List<ChangedLine> lines) {
        if (lines.isEmpty()) return List.of();

        // Phase 1: proximity grouping per file
        List<ChangeGroup> proximityGroups = proximityGroup(lines);
        log.debug("Phase 1 (proximity): {} groups", proximityGroups.size());

        // Phase 2: merge groups that share symbols across files
        List<ChangeGroup> merged = semanticMerge(proximityGroups);
        log.debug("Phase 2 (semantic merge): {} groups", merged.size());

        return merged;
    }

    // -----------------------------------------------------------------------
    // Phase 1: proximity grouping (within a single file)
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
    // Phase 2: semantic merge across files
    // -----------------------------------------------------------------------

    /**
     * Build a symbol fingerprint for each group (set of identifiers in ADD/DELETE lines),
     * then use Union-Find to cluster groups that share at least one non-trivial symbol.
     */
    private List<ChangeGroup> semanticMerge(List<ChangeGroup> groups) {
        if (groups.size() <= 1) return groups;

        int n = groups.size();
        // Build symbol sets per group
        List<Set<String>> symbolSets = new ArrayList<>();
        for (ChangeGroup g : groups) {
            symbolSets.add(extractSymbols(g));
        }

        // Union-Find
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                // Only merge cross-file groups (same-file proximity already handled)
                if (groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                if (sharesSymbol(symbolSets.get(i), symbolSets.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        // Collect groups by root
        Map<Integer, List<ChangeGroup>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }

        // Merge clusters into single ChangeGroups
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

    /** Extract meaningful symbols (length >= 3, non-keyword) from ADD/DELETE lines. */
    private Set<String> extractSymbols(ChangeGroup group) {
        Set<String> symbols = new HashSet<>();
        for (ChangedLine line : group.changedLines()) {
            if (line.type() == ChangedLine.LineType.CONTEXT) continue;
            Matcher m = SYMBOL_PATTERN.matcher(line.content());
            while (m.find()) {
                String sym = m.group(1);
                if (!STOP_WORDS.contains(sym)) {
                    symbols.add(sym);
                }
            }
        }
        return symbols;
    }

    private boolean sharesSymbol(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) return true;
        }
        return false;
    }

    private ChangeGroup mergeCluster(List<ChangeGroup> cluster) {
        String id = "G" + groupCounter.incrementAndGet();
        // Primary file = file with most changed lines
        String primary = cluster.stream()
                .max(Comparator.comparingLong(g ->
                        g.changedLines().stream()
                                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                                .count()))
                .map(ChangeGroup::primaryFile)
                .orElse(cluster.get(0).primaryFile());
        List<ChangedLine> allLines = new ArrayList<>();
        for (ChangeGroup g : cluster) allLines.addAll(g.changedLines());
        log.debug("Semantic merge: {} groups -> {} (files: {})",
                cluster.size(), id,
                cluster.stream().map(ChangeGroup::primaryFile).toList());
        return new ChangeGroup(id, primary, allLines, new ArrayList<>());
    }

    // -----------------------------------------------------------------------
    // Union-Find helpers
    // -----------------------------------------------------------------------

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
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

    // -----------------------------------------------------------------------
    // Stop words – common Java tokens that are not meaningful symbols
    // -----------------------------------------------------------------------

    private static final Set<String> STOP_WORDS = Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "void", "int", "long", "double", "float", "boolean", "byte", "short", "char",
            "class", "interface", "enum", "record", "extends", "implements", "throws",
            "return", "new", "this", "super", "null", "true", "false",
            "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
            "try", "catch", "finally", "throw", "import", "package",
            "String", "Object", "List", "Map", "Set", "Optional", "var",
            "Override", "SuppressWarnings", "Deprecated"
    );
}
