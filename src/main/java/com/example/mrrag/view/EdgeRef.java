package com.example.mrrag.view;

/**
 * A lightweight pair that couples a neighbouring {@link GraphNodeView} with
 * the source-file line number at which the edge was recorded.
 *
 * <p>Used in place of a bare {@code GraphNodeView} for all edge-based
 * collections (callers, callees, readsFields, writesFields, …) so that
 * {@link GraphNodeView#toMarkdown()} can emit the single relevant source
 * line instead of the full declaration snippet.
 *
 * <p>Examples:
 * <ul>
 *   <li>A {@code callers} entry carries the line inside the caller's body
 *       where the call expression appears.</li>
 *   <li>A {@code readsFields} entry carries the line where the field read
 *       occurs.</li>
 * </ul>
 *
 * @param view the neighbouring node view; never {@code null}
 * @param line the 1-based source line of the edge; {@code -1} for
 *             external/synthetic edges with no position information
 */
public record EdgeRef(GraphNodeView view, int line) {

    /**
     * Compact factory used by {@link com.example.mrrag.service.GraphViewBuilder}.
     *
     * @param view neighbouring view; must not be {@code null}
     * @param line 1-based source line, or {@code -1}
     * @return new {@link EdgeRef}
     */
    public static EdgeRef of(GraphNodeView view, int line) {
        return new EdgeRef(view, line);
    }
}
