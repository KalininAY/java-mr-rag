package com.example.mrrag.model;

/**
 * Represents a single changed line from a unified diff.
 *
 * @param filePath      relative path of the file in the repository
 * @param lineNumber    line number in the NEW version of the file (0 if deleted)
 * @param oldLineNumber line number in the OLD version (0 if added)
 * @param content       raw line content (without +/-)
 * @param type          ADD, DELETE, or CONTEXT
 */
public record ChangedLine(
        String filePath,
        int lineNumber,
        int oldLineNumber,
        String content,
        LineType type
) {
    public enum LineType { ADD, DELETE, CONTEXT }

    /** Returns a copy of this line re-typed as CONTEXT (used by mirror-comment merge). */
    public ChangedLine asContext() {
        return new ChangedLine(filePath, lineNumber, oldLineNumber, content, LineType.CONTEXT);
    }

    /** Alias so callers can use {@code l.text()} interchangeably with {@code l.content()}. */
    public String text() {
        return content;
    }
}
