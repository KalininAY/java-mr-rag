package com.example.mrrag.graph.markdown;

import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;

import java.util.List;

/**
 * Renders {@link GraphNode} and {@link GraphEdge} collections as GitHub-Flavored Markdown.
 *
 * <p>All methods are stateless and can be called from any thread.
 */
public final class GraphMarkdownRenderer {

    private GraphMarkdownRenderer() {}

    // ──────────────────────────────────────────────────────────────────
    // Node
    // ──────────────────────────────────────────────────────────────────

    /**
     * Renders a single node as a Markdown card.
     *
     * <pre>
     * ## Node: `com.example.Foo#bar(String)`
     *
     * | Property      | Value |
     * |---|---|
     * | Kind          | METHOD |
     * | Simple name   | bar   |
     * | File          | src/…  |
     * | Lines         | 42 – 58 |
     * | Body hash     | abc123… |
     *
     * ### Declaration
     * ```java
     * public void bar(String s)
     * ```
     *
     * ### Source
     * ```java
     * public void bar(String s) { … }
     * ```
     * </pre>
     */
    public static String renderNode(GraphNode node) {
        StringBuilder md = new StringBuilder();
        md.append("## Node: `").append(node.id()).append("`\n\n");
        md.append("| Property | Value |\n|---|---|\n");
        md.append("| **Kind**        | `").append(node.kind()).append("` |\n");
        md.append("| **Simple name** | `").append(node.simpleName()).append("` |\n");
        md.append("| **File**        | `").append(node.filePath()).append("` |\n");
        md.append("| **Lines**       | ").append(node.startLine())
          .append(" \u2013 ").append(node.endLine()).append(" |\n");
        md.append("| **Body hash**   | `").append(node.bodyHash()).append("` |\n");
        appendSnippet(md, "Declaration", node.declarationSnippet());
        appendSnippet(md, "Source",      node.sourceSnippet());
        return md.toString();
    }

    // ──────────────────────────────────────────────────────────────────
    // Edges
    // ──────────────────────────────────────────────────────────────────

    /**
     * Renders a list of edges as a Markdown table.
     *
     * @param nodeId    id of the anchor node (used in the section heading)
     * @param edges     pre-filtered list of edges to render
     * @param direction "FROM" (outgoing) or "TO" (incoming)
     * @param kindFilter optional {@link EdgeKind} that was used to filter; {@code null} = no filter
     */
    public static String renderEdges(String nodeId,
                                     List<GraphEdge> edges,
                                     String direction,
                                     EdgeKind kindFilter) {
        boolean incoming = "TO".equalsIgnoreCase(direction);
        String label = incoming
                ? "Edges TO `" + nodeId + "` (incoming)"
                : "Edges FROM `" + nodeId + "` (outgoing)";

        StringBuilder md = new StringBuilder();
        md.append("## ").append(label).append("\n\n");

        if (kindFilter != null) {
            md.append("_Filter: kind = `").append(kindFilter).append("`_\n\n");
        }

        if (edges.isEmpty()) {
            md.append("_No edges._\n");
            return md.toString();
        }

        md.append("| # | Kind | Caller | Callee | File | Line |\n");
        md.append("|---|---|---|---|---|---|\n");
        int i = 1;
        for (GraphEdge e : edges) {
            md.append("| ").append(i++).append(" | ")
              .append("`").append(e.kind()).append("` | ")
              .append("`").append(e.caller()).append("` | ")
              .append("`").append(e.callee()).append("` | ")
              .append("`").append(e.filePath()).append("` | ")
              .append(e.startLine()).append(" |\n");
        }
        return md.toString();
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────

    private static void appendSnippet(StringBuilder md, String title, String snippet) {
        if (snippet == null || snippet.isBlank()) return;
        md.append("\n### ").append(title).append("\n\n```java\n")
          .append(snippet.strip()).append("\n```\n");
    }
}
