package com.example.mrrag.context.render;

import com.example.mrrag.context.plan.ContextPlan;
import com.example.mrrag.context.plan.LineRange;

import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ContextPlan} into the pseudo-Markdown context format:
 *
 * <pre>
 * ## path/to/File.java
 * 1  | public class Foo {
 * 2  |   private int bar;
 * ...
 * 10 |   public void doThing() {
 * 11 |     bar++;
 * 12 |   }
 * ...
 * 20 | }
 * </pre>
 *
 * <p>Rules applied during rendering:
 * <ul>
 *   <li>Ranges are already sorted and merged (provided by {@link ContextPlan#merged()}).</li>
 *   <li>A gap of <strong>exactly one line</strong> between two ranges is shown as-is.</li>
 *   <li>A gap of <strong>more than one line</strong> is replaced by a single {@code ...} line.</li>
 * </ul>
 */
public final class ContextRenderer {

    private static final String ELLIPSIS = "...";

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Renders all files in the plan.
     *
     * @param plan   the context plan (ranges already deduplicated)
     * @param source file content provider
     * @return the rendered context string
     */
    public String render(ContextPlan plan, FileSource source) {
        Map<String, List<LineRange>> merged = plan.merged();
        if (merged.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        merged.forEach((filePath, ranges) -> renderFile(sb, filePath, ranges, source));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void renderFile(StringBuilder sb,
                            String filePath,
                            List<LineRange> ranges,
                            FileSource source) {
        sb.append("## ").append(filePath).append("\n");

        List<String> lines = source.lines(filePath);
        int prevLastLine = -1;

        for (LineRange range : ranges) {
            int gapSize = (prevLastLine < 0) ? 0 : range.from() - prevLastLine - 1;

            if (gapSize > 1) {
                sb.append(ELLIPSIS).append("\n");
            } else if (gapSize == 1) {
                // Exactly one skipped line — emit it
                appendLine(sb, prevLastLine + 1, lines);
            }
            // gapSize == 0 means ranges are adjacent/overlapping (already merged), no separator needed

            for (int lineNo = range.from(); lineNo <= range.to(); lineNo++) {
                appendLine(sb, lineNo, lines);
            }
            prevLastLine = range.to();
        }

        sb.append("\n");
    }

    private static void appendLine(StringBuilder sb, int lineNo, List<String> lines) {
        String content = (lineNo >= 1 && lineNo <= lines.size())
                ? lines.get(lineNo - 1)
                : "";
        sb.append(String.format("%-4d| %s%n", lineNo, content));
    }
}
