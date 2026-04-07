package com.example.mrrag.review;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Semantic diff filter: removes ADD/DELETE lines that represent a pure
 * <em>move</em> of a Java method or field within the codebase — same body
 * (or declaration), different file or line.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Collect qualified IDs of {@code METHOD} and {@code FIELD} nodes present
 *       in <em>both</em> the source and target {@link ProjectGraph}.</li>
 *   <li>Compare the <b>SHA-256 of the normalised source snippet</b> stored in
 *       {@link GraphNode#source()}. Identical hash → symbol was not changed, only moved.</li>
 *   <li>Determine line ranges of moved symbols in both graphs and remove the
 *       corresponding ADD (source) and DELETE (target) lines.</li>
 *   <li>{@code CONTEXT} lines and non-Java files are never removed.</li>
 * </ol>
 *
 * <p>The filter is intentionally conservative: on any doubt (hash mismatch,
 * missing node, range not found) the line is kept.
 */
@Slf4j
@Component
public class SemanticDiffFilter {

    /**
     * @param lines       raw changed lines from the diff parser
     * @param sourceGraph AST graph of the source (feature) branch
     * @param targetGraph AST graph of the target (base) branch
     * @return filtered list with MOVED lines removed
     */
    public List<ChangedLine> filter(
            List<ChangedLine> lines,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph
    ) {
        Set<String> movedMethodIds = findMovedNodeIds(sourceGraph, targetGraph, NodeKind.METHOD);
        Set<String> movedFieldIds  = findMovedNodeIds(sourceGraph, targetGraph, NodeKind.FIELD);

        if (movedMethodIds.isEmpty() && movedFieldIds.isEmpty()) {
            return lines;
        }

        log.debug("Moved methods: {}, moved fields: {}",
                movedMethodIds.size(), movedFieldIds.size());

        Set<LineKey> addLinesToRemove    = buildLineKeys(movedMethodIds, movedFieldIds, sourceGraph);
        Set<LineKey> deleteLinesToRemove = buildLineKeys(movedMethodIds, movedFieldIds, targetGraph);

        List<ChangedLine> result = new ArrayList<>();
        int removed = 0;
        for (ChangedLine line : lines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) { result.add(line); continue; }
            if (!line.filePath().endsWith(".java"))          { result.add(line); continue; }

            if (line.type() == ChangedLine.LineType.ADD
                    && addLinesToRemove.contains(new LineKey(line.filePath(), line.lineNumber()))) {
                log.trace("Filtering MOVED ADD  {}:{}", line.filePath(), line.lineNumber());
                removed++;
                continue;
            }
            if (line.type() == ChangedLine.LineType.DELETE
                    && deleteLinesToRemove.contains(new LineKey(line.filePath(), line.oldLineNumber()))) {
                log.trace("Filtering MOVED DEL  {}:{}", line.filePath(), line.oldLineNumber());
                removed++;
                continue;
            }
            result.add(line);
        }

        log.info("SemanticDiffFilter: removed {} MOVED lines ({} methods, {} fields)",
                removed, movedMethodIds.size(), movedFieldIds.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Identify moved nodes
    // -----------------------------------------------------------------------

    /**
     * A node is MOVED when:
     * <ul>
     *   <li>its ID exists in both graphs,</li>
     *   <li>SHA-256 of its normalised {@link GraphNode#source()} is identical,</li>
     *   <li>its file path OR start line differs (otherwise it was not moved).</li>
     * </ul>
     */
    private Set<String> findMovedNodeIds(
            ProjectGraph source, ProjectGraph target, NodeKind kind
    ) {
        Set<String> moved = new HashSet<>();
        for (Map.Entry<String, GraphNode> entry : source.nodes.entrySet()) {
            GraphNode sn = entry.getValue();
            if (sn.kind() != kind) continue;

            GraphNode tn = target.nodes.get(entry.getKey());
            if (tn == null) continue; // added, not moved

            String sh = hash(sn.source());
            String th = hash(tn.source());
            if (sh == null || !sh.equals(th)) continue; // body changed

            if (sn.filePath().equals(tn.filePath()) && sn.startLine() == tn.startLine()) continue; // same position

            moved.add(entry.getKey());
            log.debug("MOVED {}: {} from {}:{} to {}:{}",
                    kind, entry.getKey(),
                    tn.filePath(), tn.startLine(),
                    sn.filePath(), sn.startLine());
        }
        return moved;
    }

    // -----------------------------------------------------------------------
    // Build line-range sets
    // -----------------------------------------------------------------------

    private Set<LineKey> buildLineKeys(
            Set<String> methodIds, Set<String> fieldIds, ProjectGraph graph
    ) {
        Set<LineKey> set = new HashSet<>();
        for (String id : methodIds) {
            GraphNode n = graph.nodes.get(id);
            if (n != null) addRange(set, n.filePath(), n.startLine(), n.endLine());
        }
        for (String id : fieldIds) {
            GraphNode n = graph.nodes.get(id);
            if (n != null) set.add(new LineKey(n.filePath(), n.startLine()));
        }
        return set;
    }

    private void addRange(Set<LineKey> set, String file, int from, int to) {
        for (int i = from; i <= to; i++) set.add(new LineKey(file, i));
    }

    // -----------------------------------------------------------------------
    // SHA-256 helper
    // -----------------------------------------------------------------------

    private static String hash(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // Normalise: strip whitespace before hashing so indentation changes don't break equality
            String normalised = text.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty())
                    .reduce("", (a, b) -> a + "\n" + b);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Value type
    // -----------------------------------------------------------------------

    private record LineKey(String filePath, int line) {}
}
