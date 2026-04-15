package com.example.mrrag.graph;

import com.example.mrrag.graph.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only query facade over {@link ProjectGraph}.
 *
 * <p>Replaces the legacy {@code JavaIndexService} query API (which projected
 * {@code ProjectGraph} into a separate {@code ProjectIndex} structure) with
 * direct graph queries.  No cache, no projection, no mutable state.
 *
 * <h2>Key operations</h2>
 * <ul>
 *   <li>{@link #methodsInFile} / {@link #findContainingMethod} — locate METHOD nodes.</li>
 *   <li>{@link #findTopLevelType} — locate the single top-level CLASS/INTERFACE in a file.</li>
 *   <li>{@link #resolveImportSimpleName} — extract the simple class name from an import statement.</li>
 *   <li>{@link #astKeysForLines} — collect qualified IDs touched by a set of changed lines
 *       for cross-file merge in {@link com.example.mrrag.review.pipeline.ChangeGrouper}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    // ------------------------------------------------------------------
    // Method helpers
    // ------------------------------------------------------------------

    /**
     * All METHOD nodes in the given relative file path.
     */
    public List<GraphNode> methodsInFile(ProjectGraph graph, String relPath) {
        return graph.nodes.values().stream()
                .filter(n -> n.kind() == NodeKind.METHOD && relPath.equals(n.filePath()))
                .toList();
    }

    /**
     * Smallest enclosing METHOD node for {@code (relPath, lineNo)}, or empty.
     */
    public Optional<GraphNode> findContainingMethod(ProjectGraph graph, String relPath, int lineNo) {
        return methodsInFile(graph, relPath).stream()
                .filter(m -> m.startLine() <= lineNo && m.endLine() >= lineNo)
                .min(Comparator.comparingInt(m -> m.endLine() - m.startLine()));
    }

    // ------------------------------------------------------------------
    // Top-level type helpers (for package/import grouping)
    // ------------------------------------------------------------------

    /**
     * Returns the single top-level CLASS or INTERFACE node for a file, or empty
     * if the file contains zero or more than one top-level type.
     *
     * <p>Used by {@code ChangeGrouper} to assign {@code package} declarations and
     * out-of-method lines to the same bucket as the class signature, rather than
     * creating a separate group for each such line.
     */
    public Optional<GraphNode> findTopLevelType(ProjectGraph graph, String relPath) {
        List<GraphNode> types = graph.nodes.values().stream()
                .filter(n -> (n.kind() == NodeKind.CLASS || n.kind() == NodeKind.INTERFACE)
                        && relPath.equals(n.filePath()))
                .toList();
        return types.size() == 1 ? Optional.of(types.get(0)) : Optional.empty();
    }

    /**
     * Extracts the <em>simple name</em> (last segment) of the imported type from
     * a raw import-statement content string, e.g.
     * {@code "import bugbusters.modules.extensions.ExtensionsSystemProperties;"} →
     * {@code "ExtensionsSystemProperties"}.
     *
     * <p>Returns an empty Optional for static imports of members (contains '#')
     * and for lines that do not match the import pattern.
     */
    public static Optional<String> resolveImportSimpleName(String content) {
        if (content == null) return Optional.empty();
        Matcher m = IMPORT_PATTERN.matcher(content);
        if (!m.find()) return Optional.empty();
        String fqn = m.group(1);
        // static import of a member: "pkg.Class.MEMBER" — drop the member suffix
        int dot = fqn.lastIndexOf('.');
        String simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
        // skip wildcard imports
        if ("*".equals(simpleName)) return Optional.empty();
        return Optional.of(simpleName);
    }

    // ------------------------------------------------------------------
    // Moved-symbol detection (for SemanticDiffFilter)
    // ------------------------------------------------------------------

    /**
     * Returns the set of method node IDs that exist in both graphs with the same
     * body hash but at a different position (file or start line) — i.e. purely moved.
     *
     * <p>Body hash is stored in {@link GraphNode#bodyHash()}; if absent (null) the
     * method is conservatively treated as changed.
     */
    public Set<String> findMovedMethodIds(ProjectGraph source, ProjectGraph target) {
        Set<String> moved = new HashSet<>();
        for (GraphNode sn : source.nodes.values()) {
            if (sn.kind() != NodeKind.METHOD) continue;
            GraphNode tn = target.nodes.get(sn.id());
            if (tn == null) continue;                                    // added, not moved
            if (sn.bodyHash() == null || !sn.bodyHash().equals(tn.bodyHash())) continue; // changed
            if (sn.filePath().equals(tn.filePath()) && sn.startLine() == tn.startLine()) continue; // not moved
            moved.add(sn.id());
            log.debug("MOVED method: {} from {}:{} to {}:{}",
                    sn.id(), tn.filePath(), tn.startLine(), sn.filePath(), sn.startLine());
        }
        return moved;
    }

    /**
     * Returns the set of FIELD node IDs that exist in both graphs with the same
     * declaration snippet (type signature) but at a different position.
     *
     * <p>Uses {@link GraphNode#declarationSnippet()} as a proxy for the field type,
     * since {@code GraphNode} does not carry a dedicated {@code signature} field.
     */
    public Set<String> findMovedFieldIds(ProjectGraph source, ProjectGraph target) {
        Set<String> moved = new HashSet<>();
        for (GraphNode sn : source.nodes.values()) {
            if (sn.kind() != NodeKind.FIELD) continue;
            GraphNode tn = target.nodes.get(sn.id());
            if (tn == null) continue;
            String sSig = sn.declarationSnippet() != null ? sn.declarationSnippet() : "";
            String tSig = tn.declarationSnippet() != null ? tn.declarationSnippet() : "";
            if (!sSig.equals(tSig)) continue;                            // type changed
            if (sn.filePath().equals(tn.filePath()) && sn.startLine() == tn.startLine()) continue;
            moved.add(sn.id());
            log.debug("MOVED field: {} from {}:{} to {}:{}",
                    sn.id(), tn.filePath(), tn.startLine(), sn.filePath(), sn.startLine());
        }
        return moved;
    }

    /**
     * Expands a set of moved-method IDs to the full line ranges they occupy in
     * the given graph.  Used to build the ADD/DELETE line-exclusion sets.
     */
    public Set<LineKey> methodLineRanges(ProjectGraph graph, Set<String> methodIds) {
        Set<LineKey> set = new HashSet<>();
        for (String id : methodIds) {
            GraphNode n = graph.nodes.get(id);
            if (n == null) continue;
            for (int i = n.startLine(); i <= n.endLine(); i++) {
                set.add(new LineKey(n.filePath(), i));
            }
        }
        return set;
    }

    /**
     * Expands a set of moved-field IDs to their single declaration lines.
     */
    public Set<LineKey> fieldDeclLines(ProjectGraph graph, Set<String> fieldIds) {
        Set<LineKey> set = new HashSet<>();
        for (String id : fieldIds) {
            GraphNode n = graph.nodes.get(id);
            if (n != null) set.add(new LineKey(n.filePath(), n.startLine()));
        }
        return set;
    }

    // ------------------------------------------------------------------
    // AST key extraction (for ChangeGrouper cross-file merge)
    // ------------------------------------------------------------------

    /**
     * Collects all node IDs and callee IDs referenced at {@code changedLines}
     * in {@code relPath}.  Used by ChangeGrouper to find cross-file
     * semantic dependencies.
     */
    public Set<String> astKeysForLines(ProjectGraph graph, String relPath,
                                        Set<Integer> changedLines) {
        Set<String> result = new HashSet<>();
        if (changedLines.isEmpty()) return result;

        // Node IDs declared at changed lines
        for (GraphNode n : graph.nodes.values()) {
            if (!relPath.equals(n.filePath())) continue;
            if (changedLines.contains(n.startLine())) result.add(n.id());
        }

        // Callee IDs of edges originating at changed lines in this file
        for (List<GraphEdge> edges : graph.edgesFrom.values()) {
            for (GraphEdge e : edges) {
                if (!relPath.equals(e.filePath())) continue;
                if (changedLines.contains(e.line()) && e.callee() != null) {
                    result.add(e.callee());
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------

    /** (filePath, line) pair used in exclusion sets for SemanticDiffFilter. */
    public record LineKey(String filePath, int line) {}
}
