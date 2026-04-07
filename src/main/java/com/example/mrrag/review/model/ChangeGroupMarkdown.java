package com.example.mrrag.review.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Renders a {@link ChangeGroup} as a Markdown string for human inspection.
 *
 * <h2>Output format</h2>
 * <pre>
 * # Group &lt;id&gt; — &lt;primaryFile&gt;
 *
 * ## Changes
 * | # | T | Text |
 * |---|---|------|
 * path/to/File1.java
 * | 22 | + | ... |
 * | 77 | - | ... |
 * path/to/File2.java
 * | 33 |   | ... |
 *
 * ## Context — METHOD_BODY 'myMethod'  (File.java:10-40)
 * _Body of method 'myMethod()'_
 * | # | Text |
 * |-|-|
 * path/to/File.java
 * | 10 | public void myMethod() { |
 * | 11 |     ... |
 * </pre>
 */
public final class ChangeGroupMarkdown {

    private ChangeGroupMarkdown() {}

    public static String render(ChangeGroup group) {
        StringBuilder sb = new StringBuilder();

        sb.append("# Group ").append(group.id())
          .append(" — ").append(group.primaryFile()).append("\n\n");

        appendChanges(sb, group.changedLines());

        for (EnrichmentSnippet snippet : group.enrichments()) {
            appendSnippet(sb, snippet);
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------

    private static void appendChanges(StringBuilder sb, List<ChangedLine> lines) {
        sb.append("## Changes\n");
        sb.append("|#|T|Text|\n");
        sb.append("|-|-|-|\n");

        // Group consecutive lines by file, preserving order
        SequencedMap<String, List<ChangedLine>> byFile = groupByFile(lines);

        byFile.forEach((file, fileLines) -> {
            sb.append(file).append("\n");
            for (ChangedLine l : fileLines) {
                int num = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
                String marker = switch (l.type()) {
                    case ADD     -> "+";
                    case DELETE  -> "-";
                    case CONTEXT -> " ";
                };
                sb.append("|")
                  .append(num).append("|")
                  .append(marker).append("|")
                  .append(escapePipe(l.content())).append("|\n");
            }
        });
        sb.append("\n");
    }

    private static void appendSnippet(StringBuilder sb, EnrichmentSnippet s) {
        sb.append("## Context — ").append(s.type()).append(" '").append(s.symbolName()).append("'")
          .append("  (").append(s.filePath()).append(":").append(s.startLine());
        if (s.endLine() != s.startLine()) sb.append("-").append(s.endLine());
        sb.append(")\n");

        if (s.explanation() != null && !s.explanation().isBlank()) {
            sb.append("_").append(s.explanation()).append("_\n");
        }

        sb.append("|#|Text|\n");
        sb.append("|-|-|\n");

        // Lines in a snippet come from one file; emit filename header once
        sb.append(s.filePath()).append("\n");
        int lineNo = s.startLine();
        for (String line : s.lines()) {
            sb.append("|").append(lineNo).append("|")
              .append(escapePipe(line)).append("|\n");
            lineNo++;
        }
        sb.append("\n");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Groups lines by filePath preserving insertion order. */
    private static SequencedMap<String, List<ChangedLine>> groupByFile(List<ChangedLine> lines) {
        SequencedMap<String, List<ChangedLine>> map = new LinkedHashMap<>();
        for (ChangedLine l : lines) {
            map.computeIfAbsent(l.filePath(), k -> new java.util.ArrayList<>()).add(l);
        }
        return map;
    }

    /** Escapes pipe characters so they don't break Markdown tables. */
    private static String escapePipe(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|");
    }
}
