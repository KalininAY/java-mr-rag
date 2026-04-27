package com.example.mrrag.review.strategy;

import com.example.mrrag.graph.model.GraphNode;

/**
 * Extracts a small window of lines around a specific call-site line
 * from a {@link GraphNode#sourceSnippet()}.
 *
 * <p>Used for {@code METHOD_CALLERS} and {@code FIELD_USAGES} snippets
 * so that the LLM sees only the relevant call context,
 * not the entire caller method body.
 */
public final class CallerSnippetExtractor {

    private CallerSnippetExtractor() {}

    /**
     * Extracts up to {@code 2*windowLines + 1} lines centred on {@code callLine}
     * from the node's {@link GraphNode#sourceSnippet()}.
     *
     * <p>The method signature line ({@link GraphNode#startLine()}) is always
     * prepended (if it is not already inside the window) so the reader knows
     * which method the call belongs to.
     *
     * @param caller     the calling method node (provides sourceSnippet and startLine)
     * @param callLine   absolute line number of the call site
     * @param windowLines number of lines above and below the call site (typically 3-5)
     * @return extracted snippet text, or {@code caller.sourceSnippet()} if extraction fails
     */
    public static String extract(GraphNode caller, int callLine, int windowLines) {
        String src = caller.sourceSnippet();
        if (src == null || src.isBlank() || callLine <= 0) return src;

        String[] lines = src.split("\n", -1);
        int callerStart = caller.startLine();
        if (callerStart <= 0) return src;

        // convert absolute line numbers to 0-based indices into the snippet array
        int callIdx  = callLine  - callerStart;  // 0-based index of call line
        int sigIdx   = 0;                         // 0-based index of method signature

        if (callIdx < 0 || callIdx >= lines.length) return src;

        int winFrom = Math.max(0,            callIdx - windowLines);
        int winTo   = Math.min(lines.length, callIdx + windowLines + 1);

        StringBuilder sb = new StringBuilder();

        // prepend signature line if it falls outside the window
        if (sigIdx < winFrom) {
            sb.append(lines[sigIdx]).append('\n');
            if (winFrom > sigIdx + 1) {
                sb.append("    // ...\n");
            }
        }

        for (int i = winFrom; i < winTo; i++) {
            sb.append(lines[i]).append('\n');
        }

        // trim trailing newline
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Returns the absolute start line of the window (for use as snippet {@code startLine}).
     */
    public static int windowStartLine(GraphNode caller, int callLine, int windowLines) {
        if (caller.startLine() <= 0 || callLine <= 0) return caller.startLine();
        return Math.max(caller.startLine(), callLine - windowLines);
    }

    /**
     * Returns the absolute end line of the window (for use as snippet {@code endLine}).
     */
    public static int windowEndLine(GraphNode caller, int callLine, int windowLines) {
        if (caller.startLine() <= 0 || callLine <= 0) return caller.endLine();
        return Math.min(caller.endLine(), callLine + windowLines);
    }
}
