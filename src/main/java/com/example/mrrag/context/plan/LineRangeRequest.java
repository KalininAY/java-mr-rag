package com.example.mrrag.context.plan;

/**
 * A request to include a line range from a file into the context.
 *
 * @param filePath graph-normalised relative file path
 * @param range    inclusive line range to include
 * @param reason   human-readable explanation (for debugging / logging)
 */
public record LineRangeRequest(String filePath, LineRange range, String reason) {

    public LineRangeRequest {
        if (filePath == null || filePath.isBlank())
            throw new IllegalArgumentException("filePath must not be blank");
    }

    /** Convenience factory — avoids constructing a {@link LineRange} inline. */
    public static LineRangeRequest of(String filePath, int from, int to, String reason) {
        return new LineRangeRequest(filePath, new LineRange(from, to), reason);
    }
}
