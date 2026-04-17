package com.example.mrrag.graph.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for rendering numbered source-code snippets in Markdown.
 *
 * <p>Extracted from {@link MarkdownNode} so that other layers (e.g.
 * {@code review.pipeline}) can produce the same
 * {@code lineNo| content} format without depending on the full
 * {@code MarkdownNode} hierarchy.
 *
 * <p>Format per line:
 * <pre>
 * 55| public static void takeScreenshotDependingOnEnvironment() {
 * 56|     if (System.getProperty(...
 * </pre>
 * When the snippet line-count does not match the {@code startLine–endLine} span
 * (e.g. Spoon {@code toString()} fallback), a warning note is prepended and
 * relative numbers (1..N) are used instead of absolute ones.
 */
public final class MarkdownRenderUtils {

    private MarkdownRenderUtils() {}

    /**
     * Appends a numbered snippet to {@code sb}.
     *
     * <p>Special cases:
     * <ul>
     *   <li>{@code startLine == -1} — external/library symbol: lines numbered {@code -1, -2, …}</li>
     *   <li>{@code snippet} is blank — appends {@code "0|(empty)"}</li>
     *   <li>line count ≠ span — warning note + relative numbering</li>
     * </ul>
     *
     * @param sb        target builder
     * @param snippet   raw source text (may be {@code null})
     * @param startLine first file line of the snippet ({@code -1} for external)
     * @param endLine   last file line of the snippet (ignored when {@code startLine <= 0})
     */
    public static void appendNumberedSnippet(
            StringBuilder sb, String snippet, int startLine, int endLine) {
        if (startLine == -1) {
            if (snippet == null || snippet.isBlank()) {
                sb.append("-1|(external)\n");
                return;
            }
            String[] lines = snippet.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(-(i + 1)).append('|').append(lines[i]).append('\n');
            }
            return;
        }
        if (snippet == null || snippet.isBlank()) {
            sb.append("0|(empty)\n");
            return;
        }
        String[] lines = snippet.split("\n", -1);
        for (String line : numberedSnippetLines(lines, startLine, endLine, true)) {
            sb.append(line).append('\n');
        }
    }

    /**
     * Same as {@link #appendNumberedSnippet} but returns the lines as a list
     * instead of writing to a {@code StringBuilder}.
     */
    public static List<String> numberedSnippetLines(
            String[] lines, int startLine, int endLine, boolean fullSpan) {
        List<String> out = new ArrayList<>(lines.length + 1);
        if (lines.length == 0) return out;

        if (startLine <= 0) {
            for (int i = 0; i < lines.length; i++) {
                out.add((1 + i) + "|" + lines[i]);
            }
            return out;
        }

        int expected = (endLine >= startLine) ? (endLine - startLine + 1) : -1;
        if (fullSpan && expected > 0 && lines.length != expected) {
            out.add("*Line numbers below are relative (snippet has " + lines.length
                    + " lines; graph span is " + expected
                    + " lines " + startLine + "\u2013" + endLine + ")*");
            for (int i = 0; i < lines.length; i++) {
                out.add((i + 1) + "|" + lines[i]);
            }
            return out;
        }

        for (int i = 0; i < lines.length; i++) {
            out.add((startLine + i) + "|" + lines[i]);
        }
        return out;
    }
}
