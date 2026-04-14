package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.ChangeGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Two-phase grouping:
 * <ol>
 *   <li><b>Code-block grouping (intra-file)</b> – changed lines inside the
 *       same method body are grouped together; lines outside any method each
 *       form their own group.  Uses the AST graph rather than arbitrary
 *       line-distance heuristics.  Purely structural lines (lone braces,
 *       try/catch/finally keywords) are dropped first, and mirror-comment
 *       pairs (DELETE x / ADD {@code // x}) are collapsed into one group.</li>
 *   <li><b>Cross-file AST merge</b> – groups from different files are merged
 *       when they share resolved symbol references (method declared in A
 *       called in B, etc.) via Union-Find on qualified node/edge IDs.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private final GraphQueryService graphQuery;
    private final AtomicInteger groupCounter = new AtomicInteger(0);

    private static final Pattern STRUCTURAL_CONTENT = Pattern.compile(
            "^[\\{\\}\\(\\)\\[\\]\\s]*"
            + "(?:(?:try|catch(?:\\s*\\([^)]*\\))?|finally|else(?:\\s+if\\s*\\([^)]*\\))?)"
            + "[\\{\\}\\(\\)\\[\\]\\s]*)?"
            + "$"
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Group without AST graph (line-distance fallback). */
    public List<ChangeGroup> group(Set<ChangedLine> lines) {
        return group(lines, null);
    }

    /**
     * Group using AST graph for method-container lookup and cross-file merge.
     *
     * @param lines changed lines from the diff parser / SemanticDiffFilter
     * @param graph AST graph of the source branch (may be {@code null} for fallback)
     */
    public List<ChangeGroup> group(Set<ChangedLine> lines, ProjectGraph graph) {
        List<ChangedLine> meaningful = lines.stream()
                .filter(l -> l.type() == ChangedLine.LineType.CONTEXT || !isStructural(l.content()))
                .toList();
        int dropped = lines.size() - meaningful.size();
        if (dropped > 0) log.debug("Structural-line filter: dropped {}", dropped);

        List<ChangedLine> merged = mergeMirrorCommentPairs(meaningful);
        int mirrorMerged = meaningful.size() - merged.size();
        if (mirrorMerged > 0) log.debug("Mirror-comment merge: collapsed {} lines into pairs", mirrorMerged);

        List<ChangeGroup> phase1 = codeBlockGroup(merged, graph);
        log.debug("Phase 1 (code-block): {} groups", phase1.size());
        List<ChangeGroup> phase2 = semanticMerge(phase1, graph);
        log.debug("Phase 2 (cross-file AST): {} groups", phase2.size());
        return phase2;
    }

    // -----------------------------------------------------------------------
    // Structural-line detection
    // -----------------------------------------------------------------------

    static boolean isStructural(String text) {
        if (text == null) return true;
        String stripped = text.strip();
        if (stripped.isEmpty()) return true;
        String content = stripped.startsWith("//") ? stripped.substring(2).strip() : stripped;
        return STRUCTURAL_CONTENT.matcher(content).matches();
    }

    // -----------------------------------------------------------------------
    // Mirror-comment pair merging
    // -----------------------------------------------------------------------

    private List<ChangedLine> mergeMirrorCommentPairs(List<ChangedLine> lines) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        List<ChangedLine> result = new ArrayList<>();
        for (List<ChangedLine> fileLines : byFile.values()) result.addAll(mergeMirrorInFile(fileLines));
        return result;
    }

    private List<ChangedLine> mergeMirrorInFile(List<ChangedLine> lines) {
        int n = lines.size();
        boolean[] consumed = new boolean[n];
        List<ChangedLine> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            ChangedLine del = lines.get(i);
            if (del.type() != ChangedLine.LineType.DELETE) { out.add(del); continue; }
            String delNorm = normaliseForMirror(del.content());
            boolean paired = false;
            for (int j = i + 1; j < Math.min(n, i + 4); j++) {
                if (consumed[j]) continue;
                ChangedLine add = lines.get(j);
                if (add.type() != ChangedLine.LineType.ADD) continue;
                String addNorm = normaliseForMirror(uncomment(add.content()));
                if (delNorm.equals(addNorm) && !delNorm.isBlank()) {
                    consumed[j] = true; consumed[i] = true;
                    out.add(del.asContext()); out.add(add);
                    paired = true;
                    log.trace("Mirror pair merged: '{}'", delNorm);
                    break;
                }
            }
            if (!paired) out.add(del);
        }
        return out;
    }

    private static String uncomment(String text) {
        if (text == null) return "";
        String s = text.strip();
        return s.startsWith("//") ? s.substring(2).strip() : s;
    }

    private static String normaliseForMirror(String text) {
        if (text == null) return "";
        return uncomment(text).toLowerCase(java.util.Locale.ROOT);
    }

    // -----------------------------------------------------------------------
    // Phase 1: code-block grouping within each file
    // -----------------------------------------------------------------------

    private List<ChangeGroup> codeBlockGroup(List<ChangedLine> lines, ProjectGraph graph) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        List<ChangeGroup> result = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            result.addAll(groupByCodeBlock(entry.getKey(), entry.getValue(), graph));
        }
        return result;
    }

    private List<ChangeGroup> groupByCodeBlock(String file, List<ChangedLine> lines,
                                                ProjectGraph graph) {
        Map<Integer, List<ChangedLine>> buckets = new LinkedHashMap<>();
        int outOfMethodCounter = -1;

        for (ChangedLine line : lines) {
            int lineNo = effectiveLine(line);
            Integer bucketKey;

            if (graph != null && lineNo > 0) {
                Optional<GraphNode> container = graphQuery.findContainingMethod(graph, file, lineNo);
                bucketKey = container.map(GraphNode::startLine).orElse(outOfMethodCounter--);
            } else {
                bucketKey = outOfMethodCounter--;
            }
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(line);
        }

        List<ChangeGroup> groups = new ArrayList<>();
        for (List<ChangedLine> bucket : buckets.values()) {
            if (bucket.stream().allMatch(l -> l.type() == ChangedLine.LineType.CONTEXT)) continue;
            String id = "G" + groupCounter.incrementAndGet();
            groups.add(new ChangeGroup(id, file, bucket, new ArrayList<>()));
        }
        return groups;
    }

    private int effectiveLine(ChangedLine l) {
        return l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
    }

    // -----------------------------------------------------------------------
    // Phase 2: cross-file AST merge via Union-Find
    // -----------------------------------------------------------------------

    private List<ChangeGroup> semanticMerge(List<ChangeGroup> groups, ProjectGraph graph) {
        if (groups.size() <= 1 || graph == null) return groups;

        int n = groups.size();
        List<Set<String>> keys = new ArrayList<>();
        for (ChangeGroup g : groups) keys.add(buildAstKeys(g, graph));

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                if (!Collections.disjoint(keys.get(i), keys.get(j))) union(parent, i, j);
            }
        }

        Map<Integer, List<ChangeGroup>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }

        List<ChangeGroup> result = new ArrayList<>();
        for (List<ChangeGroup> cluster : clusters.values()) {
            if (cluster.size() == 1) { result.add(cluster.get(0)); continue; }
            log.debug("Cross-file merge: {} groups -> one (files: {})",
                    cluster.size(), cluster.stream().map(ChangeGroup::primaryFile).toList());
            result.add(mergeCluster(cluster));
        }
        return result;
    }

    private Set<String> buildAstKeys(ChangeGroup group, ProjectGraph graph) {
        Set<Integer> changedLines = new HashSet<>();
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.lineNumber()    > 0) changedLines.add(l.lineNumber());
            if (l.oldLineNumber() > 0) changedLines.add(l.oldLineNumber());
        }
        return graphQuery.astKeysForLines(graph, group.primaryFile(), changedLines);
    }

    private ChangeGroup mergeCluster(List<ChangeGroup> cluster) {
        String id = "G" + groupCounter.incrementAndGet();
        String primary = cluster.stream()
                .max(Comparator.comparingLong(g -> g.changedLines().stream()
                        .filter(l -> l.type() != ChangedLine.LineType.CONTEXT).count()))
                .map(ChangeGroup::primaryFile)
                .orElse(cluster.get(0).primaryFile());
        List<ChangedLine> allLines = new ArrayList<>();
        for (ChangeGroup g : cluster) allLines.addAll(g.changedLines());
        return new ChangeGroup(id, primary, allLines, new ArrayList<>());
    }

    // Union-Find
    private int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i;
    }
    private void union(int[] parent, int a, int b) { parent[find(parent, a)] = find(parent, b); }
}
