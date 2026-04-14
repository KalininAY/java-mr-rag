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
 */
@Slf4j
@Component
public class DiffParser {

    /**
     * Parse all diffs from a MR into a flat list of changed lines.
     * Only ADD and DELETE lines are included; CONTEXT lines are also preserved
     * to help grouper understand proximity.
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
     * <p>
     * Unified diff format:
     * @@ -oldStart,oldCount +newStart,newCount @@
     * -deleted line
     * +added line
     *  context line
     */
    private List<ChangedLine> parseFileDiff(Diff diff) {
        List<ChangedLine> lines = new ArrayList<>();
        String filePath = diff.getNewPath() != null ? diff.getNewPath() : diff.getOldPath();

        int newLine = 0;
        int oldLine = 0;

        for (String raw : diff.getDiff().split("\n", -1)) {
            if (raw.startsWith("@@")) {
                // Parse hunk header: @@ -oldStart[,oldCount] +newStart[,newCount] @@
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
                // context line (starts with space)
                String content = !raw.isEmpty() ? raw.substring(1) : "";
                lines.add(new ChangedLine(filePath, newLine, oldLine, content, ChangedLine.LineType.CONTEXT));
                newLine++;
                oldLine++;
            }
        }
        return lines;
    }

    /**
     * Parse @@ -oldStart,oldCount +newStart,newCount @@ into [oldStart, newStart].
     */
    private int[] parseHunkHeader(String header) {
        // Example: @@ -10,6 +12,8 @@ some context
        try {
            int minusIdx = header.indexOf('-');
            int plusIdx = header.indexOf('+', minusIdx);
            int spaceAfterMinus = header.indexOf(' ', minusIdx);
            int spaceAfterPlus = header.indexOf(' ', plusIdx);

            String oldPart = header.substring(minusIdx + 1, spaceAfterMinus);
            String newPart = header.substring(plusIdx + 1,
                    spaceAfterPlus > 0 ? spaceAfterPlus : header.length());

            int oldStart = Integer.parseInt(oldPart.contains(",")
                    ? oldPart.substring(0, oldPart.indexOf(','))
                    : oldPart);
            int newStart = Integer.parseInt(newPart.contains(",")
                    ? newPart.substring(0, newPart.indexOf(','))
                    : newPart);

            return new int[]{oldStart, newStart};
        } catch (Exception e) {
            log.warn("Failed to parse hunk header: {}", header);
            return new int[]{1, 1};
        }
    }
}
