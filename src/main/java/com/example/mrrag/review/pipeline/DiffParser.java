package com.example.mrrag.review.pipeline;

import com.example.mrrag.review.model.ChangedLine;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.Diff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses GitLab Diff objects (unified diff format) into structured ChangedLine lists.
 *
 * <p>Only ADD and DELETE lines are emitted. CONTEXT lines are used internally
 * to track line-number offsets but are <b>not</b> included in the result —
 * keeping the downstream model free of presentation concerns.
 *
 * <p>If context lines are ever needed (e.g. for proximity grouping), the
 * {@link ChangeGrouper} should re-read them from the raw diff rather than
 * relying on this parser to pass them through.
 */
@Slf4j
@Component
public class DiffParser {

    /**
     * Parse all diffs from an MR into a flat set of ADD/DELETE changed lines.
     */
    public Set<ChangedLine> parse(List<Diff> diffs) {
        Set<ChangedLine> result = new LinkedHashSet<>();
        for (Diff diff : diffs) {
            if (diff.getDiff() == null || diff.getDiff().isBlank()) continue;
            if (isNonJavaFile(diff.getNewPath())) continue;
            result.addAll(parseFileDiff(diff));
        }
        return result;
    }

    private boolean isNonJavaFile(String path) {
        return path == null || !path.endsWith(".java");
    }

    /**
     * Parse a single file's unified diff string.
     *
     * <p>Unified diff format:
     * <pre>
     * @@ -oldStart,oldCount +newStart,newCount @@
     * -deleted line
     * +added line
     *  context line
     * </pre>
     *
     * CONTEXT lines advance both counters but are NOT emitted.
     */
    private List<ChangedLine> parseFileDiff(Diff diff) {
        List<ChangedLine> lines = new ArrayList<>();
        String filePath = diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();

        int newLine = 0;
        int oldLine = 0;

        for (String raw : diff.getDiff().split("\n", -1)) {
            if (raw.startsWith("@@")) {
                int[] starts = parseHunkHeader(raw);
                oldLine = starts[0];
                newLine = starts[1];
                continue;
            }
            if (raw.startsWith("---") || raw.startsWith("+++ ")) continue;

            if (raw.startsWith("+")) {
                lines.add(new ChangedLine(filePath, newLine, 0, raw.substring(1), ChangedLine.LineType.ADD));
                newLine++;
            } else if (raw.startsWith("-")) {
                lines.add(new ChangedLine(filePath, 0, oldLine, raw.substring(1), ChangedLine.LineType.DELETE));
                oldLine++;
            } else {
                // context line — advance counters only, do NOT emit
                newLine++;
                oldLine++;
            }
        }
        return lines;
    }

    /**
     * Parse {@code @@ -oldStart,oldCount +newStart,newCount @@} into {@code [oldStart, newStart]}.
     */
    private int[] parseHunkHeader(String header) {
        try {
            int minusIdx = header.indexOf('-');
            int plusIdx = header.indexOf('+', minusIdx);
            int spaceAfterMinus = header.indexOf(' ', minusIdx);
            int spaceAfterPlus = header.indexOf(' ', plusIdx);

            String oldPart = header.substring(minusIdx + 1, spaceAfterMinus);
            String newPart = header.substring(plusIdx + 1,
                    spaceAfterPlus > 0 ? spaceAfterPlus : header.length());

            int oldStart = Integer.parseInt(oldPart.contains(",")
                    ? oldPart.substring(0, oldPart.indexOf(',')) : oldPart);
            int newStart = Integer.parseInt(newPart.contains(",")
                    ? newPart.substring(0, newPart.indexOf(',')) : newPart);

            return new int[]{oldStart, newStart};
        } catch (Exception e) {
            log.warn("Failed to parse hunk header: {}", header);
            return new int[]{1, 1};
        }
    }
}
