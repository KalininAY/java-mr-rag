package com.example.mrrag.service;

import com.example.mrrag.model.ChangedLine;
import com.example.mrrag.model.ChangeGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Grouping strategy:
 * <ol>
 *   <li>Lines in the same file are grouped if they are within {@code proximityThreshold} lines
 *       of each other (contiguous hunk proximity).</li>
 *   <li>Each hunk (set of contiguous added/deleted lines) becomes a candidate group.
 *       Hunks in the same file that are close together are merged.</li>
 *   <li>Context lines (CONTEXT type) are not split boundaries - they help connect nearby hunks.</li>
 * </ol>
 */
@Slf4j
@Component
public class ChangeGrouper {

    private static final int PROXIMITY_THRESHOLD = 20; // lines
    private final AtomicInteger groupCounter = new AtomicInteger(0);

    /**
     * Group all changed lines into ChangeGroups.
     * @param lines all changed lines (ADD, DELETE, CONTEXT) from the diff
     * @return list of ChangeGroups, each with its changed lines (no enrichments yet)
     */
    public List<ChangeGroup> group(List<ChangedLine> lines) {
        if (lines.isEmpty()) return List.of();

        // Step 1: Separate by file
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine line : lines) {
            byFile.computeIfAbsent(line.filePath(), k -> new ArrayList<>()).add(line);
        }

        List<ChangeGroup> groups = new ArrayList<>();

        // Step 2: For each file, split into hunks, then merge nearby ones
        for (Map.Entry<String, List<ChangedLine>> entry : byFile.entrySet()) {
            String file = entry.getKey();
            List<ChangedLine> fileLines = entry.getValue();
            groups.addAll(groupFileLines(file, fileLines));
        }

        log.debug("Grouped {} changed lines into {} groups", lines.size(), groups.size());
        return groups;
    }

    private List<ChangeGroup> groupFileLines(String file, List<ChangedLine> lines) {
        // Split into hunks: sequences of ADD/DELETE, separated by gaps of CONTEXT-only lines
        List<List<ChangedLine>> hunks = splitIntoHunks(lines);

        // Merge hunks that are close together
        List<List<ChangedLine>> mergedHunks = mergeNearbyHunks(hunks);

        List<ChangeGroup> groups = new ArrayList<>();
        for (List<ChangedLine> hunk : mergedHunks) {
            if (hunk.stream().anyMatch(l -> l.type() != ChangedLine.LineType.CONTEXT)) {
                String groupId = "G" + groupCounter.incrementAndGet();
                groups.add(new ChangeGroup(groupId, file, hunk, new ArrayList<>()));
            }
        }
        return groups;
    }

    /**
     * Split lines into hunks: each hunk starts and ends at ADD/DELETE lines.
     * Context-only sequences between hunks are kept with the nearest hunk.
     */
    private List<List<ChangedLine>> splitIntoHunks(List<ChangedLine> lines) {
        List<List<ChangedLine>> hunks = new ArrayList<>();
        List<ChangedLine> current = new ArrayList<>();

        for (ChangedLine line : lines) {
            if (line.type() != ChangedLine.LineType.CONTEXT) {
                current.add(line);
            } else {
                if (!current.isEmpty()) {
                    current.add(line); // attach trailing context to previous hunk
                }
                // Leading context before first ADD/DELETE is ignored
            }
        }
        if (!current.isEmpty()) hunks.add(current);

        // Re-split at long context gaps (> PROXIMITY_THRESHOLD context lines in a row)
        List<List<ChangedLine>> result = new ArrayList<>();
        for (List<ChangedLine> hunk : hunks) {
            result.addAll(splitAtContextGaps(hunk));
        }
        return result;
    }

    private List<List<ChangedLine>> splitAtContextGaps(List<ChangedLine> hunk) {
        List<List<ChangedLine>> result = new ArrayList<>();
        List<ChangedLine> current = new ArrayList<>();
        int contextCount = 0;

        for (ChangedLine line : hunk) {
            if (line.type() == ChangedLine.LineType.CONTEXT) {
                contextCount++;
                if (contextCount > PROXIMITY_THRESHOLD && !current.isEmpty()) {
                    result.add(current);
                    current = new ArrayList<>();
                    contextCount = 0;
                    continue;
                }
            } else {
                contextCount = 0;
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
            int gap = lineGap(current, next);
            if (gap <= PROXIMITY_THRESHOLD) {
                current.addAll(next);
            } else {
                merged.add(current);
                current = new ArrayList<>(next);
            }
        }
        merged.add(current);
        return merged;
    }

    /** Estimate line gap between end of one hunk and start of another. */
    private int lineGap(List<ChangedLine> a, List<ChangedLine> b) {
        int endA = lastEffectiveLine(a);
        int startB = firstEffectiveLine(b);
        return Math.abs(startB - endA);
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
