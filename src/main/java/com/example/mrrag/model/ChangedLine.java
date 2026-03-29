package com.example.mrrag.model;

/**
 * Represents a single changed line from a unified diff.
 *
 * @param filePath   relative path of the file in the repository
 * @param lineNumber line number in the NEW version of the file (0 if deleted)
 * @param oldLineNumber line number in the OLD version (0 if added)
 * @param content    raw line content (without +/-)
 * @param type       ADD, DELETE, or CONTEXT
 */
public record ChangedLine(
        String filePath,
        int lineNumber,
        int oldLineNumber,
        String content,
        LineType type
) {
    public enum LineType { ADD, DELETE, CONTEXT }
}
