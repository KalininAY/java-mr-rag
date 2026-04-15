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

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Three-phase grouping:
 * <ol>
 *   <li><b>Code-block grouping (intra-file)</b> – changed lines inside the same method
 *       body are grouped together.  Lines outside any method are bucketed by the
 *       single top-level CLASS/INTERFACE of the file (so {@code package} declaration
 *       and class signature share one group); if no unique top-level type exists the
 *       line gets its own group as before.</li>
 *   <li><b>Import attachment</b> – ADD/DELETE {@code import} lines are not kept as
 *       separate groups.  Each import is dissolved into every same-file group whose
 *       changed lines reference the imported simple name.  An import that matches no
 *       group in the same file is attached to the class-signature group (or kept as
 *       its own group if no class-signature group exists).</li>
 *   <li><b>Cross-file AST merge</b> – groups from different files are merged when they
 *       share resolved symbol references via Union-Find on qualified node/edge IDs.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private final GraphQueryService graphQuery;
    private final AtomicInteger groupCounter = new AtomicInteger(0);

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
        List<ChangeGroup> phase1 = codeBlockGroup(lines, graph);
        log.debug("Phase 1 (code-block): {} groups", phase1.size());
        List<ChangeGroup> phase2 = attachImports(phase1, graph);
        log.debug("Phase 2 (import-attach): {} groups", phase2.size());
        List<ChangeGroup> phase3 = semanticMerge(phase2, graph);
        log.debug("Phase 3 (cross-file AST): {} groups", phase3.size());
        return phase3;
    }

    // -----------------------------------------------------------------------
    // Phase 1: code-block grouping within each file
    // -----------------------------------------------------------------------

    private List<ChangeGroup> codeBlockGroup(Collection<ChangedLine> lines, ProjectGraph graph) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        List<ChangeGroup> result = new ArrayList<>();
        for (var entry : byFile.entrySet()) {
            result.addAll(groupByCodeBlock(entry.getKey(), entry.getValue(), graph));
        }
        return result;
    }

    private List<ChangeGroup> groupByCodeBlock(String file, List<ChangedLine> lines, ProjectGraph graph) {
        Map<Integer, List<ChangedLine>> buckets = new LinkedHashMap<>();
        int outOfMethodCounter = -1;

        // Pre-compute the single top-level type bucket key for this file (may be absent).
        // "package" declarations and other out-of-method lines are assigned to that
        // bucket so they share a group with the class signature line.
        final Integer topLevelKey;
        if (graph != null) {
            topLevelKey = graphQuery.findTopLevelType(graph, file)
                    .map(GraphNode::startLine)
                    .orElse(null);
        } else {
            topLevelKey = null;
        }

        for (ChangedLine line : lines) {
            // Import lines are handled separately in Phase 2 — skip them here
            // so they do not pollute the code-block buckets.
            if (isImportLine(line)) continue;

            int lineNo = effectiveLine(line);
            Integer bucketKey;

            if (graph != null && lineNo > 0) {
                Optional<GraphNode> method = graphQuery.findContainingMethod(graph, file, lineNo);
                if (method.isPresent()) {
                    bucketKey = method.get().startLine();
                } else if (topLevelKey != null) {
                    // out-of-method but there is a unique top-level type: share its bucket
                    bucketKey = topLevelKey;
                } else {
                    bucketKey = outOfMethodCounter--;
                }
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

    // -----------------------------------------------------------------------
    // Phase 2: attach import lines to the groups that reference the imported type
    // -----------------------------------------------------------------------

    /**
     * For every ADD/DELETE {@code import} line found in Phase-1 singleton groups,
     * dissolve that group and attach the import line directly to each same-file
     * group whose changed content contains the imported simple name.
     *
     * <p>Attachment rules:
     * <ol>
     *   <li>Collect all import lines that ended up as singleton groups (or are mixed
     *       into a group that consists only of imports and CONTEXT lines).</li>
     *   <li>For each such import, determine the imported simple name via
     *       {@link GraphQueryService#resolveImportSimpleName}.</li>
     *   <li>Search all other groups in the same file for non-CONTEXT lines whose
     *       {@code content} contains the simple name as a word.</li>
     *   <li>Add the import line to every matching group.</li>
     *   <li>If no group matched, fall back: attach to the class-signature group
     *       (the one whose changed lines include a line matching {@code class .* \{}
     *       or {@code interface .* \{}), or keep as its own group if none exists.</li>
     * </ol>
     */
    private List<ChangeGroup> attachImports(List<ChangeGroup> groups, ProjectGraph graph) {
        // Separate import-only groups from regular groups
        List<ChangeGroup> importGroups = new ArrayList<>();
        List<ChangeGroup> regularGroups = new ArrayList<>();

        for (ChangeGroup g : groups) {
            if (isImportOnlyGroup(g)) {
                importGroups.add(g);
            } else {
                regularGroups.add(g);
            }
        }

        if (importGroups.isEmpty()) return groups;

        // Index regular groups by file for fast lookup
        Map<String, List<ChangeGroup>> regularByFile = new LinkedHashMap<>();
        for (ChangeGroup g : regularGroups) {
            regularByFile.computeIfAbsent(g.primaryFile(), k -> new ArrayList<>()).add(g);
        }

        // Tracks which regular groups received at least one import line
        // (we mutate changedLines lists in-place via helper)
        Set<ChangeGroup> orphanImportGroups = new LinkedHashSet<>();

        for (ChangeGroup importGroup : importGroups) {
            String file = importGroup.primaryFile();
            List<ChangeGroup> sameFileGroups = regularByFile.getOrDefault(file, List.of());

            for (ChangedLine importLine : importGroup.changedLines()) {
                if (importLine.type() == ChangedLine.LineType.CONTEXT) continue;
                if (!isImportLine(importLine)) continue;

                Optional<String> simpleName = GraphQueryService.resolveImportSimpleName(importLine.content());
                if (simpleName.isEmpty()) {
                    // wildcard or unresolvable — fall back to class-signature group
                    attachToClassSignatureOrKeep(importLine, file, sameFileGroups, orphanImportGroups, importGroup);
                    continue;
                }

                String name = simpleName.get();
                List<ChangeGroup> targets = sameFileGroups.stream()
                        .filter(g -> groupReferencesName(g, name))
                        .toList();

                if (targets.isEmpty()) {
                    attachToClassSignatureOrKeep(importLine, file, sameFileGroups, orphanImportGroups, importGroup);
                    log.debug("Import '{}' in {} not matched to any group — attached to class-signature group",
                            name, file);
                } else {
                    for (ChangeGroup target : targets) {
                        target.changedLines().add(0, importLine);
                        log.debug("Import '{}' attached to group {} in {}", name, target.id(), file);
                    }
                }
            }
        }

        // Orphan import groups that could not be attached anywhere remain as-is
        List<ChangeGroup> result = new ArrayList<>(regularGroups);
        result.addAll(orphanImportGroups);
        return result;
    }

    /** True when every ADD/DELETE line in the group is an import statement. */
    private static boolean isImportOnlyGroup(ChangeGroup g) {
        return g.changedLines().stream()
                .filter(l -> l.type() != ChangedLine.LineType.CONTEXT)
                .allMatch(ChangeGrouper::isImportLine);
    }

    /** True when the line content starts with {@code import } (after stripping). */
    private static boolean isImportLine(ChangedLine l) {
        String c = l.content();
        return c != null && c.strip().startsWith("import ");
    }

    /**
     * Returns true when any non-CONTEXT line in the group contains
     * {@code name} as a word (surrounded by non-word characters).
     */
    private static boolean groupReferencesName(ChangeGroup g, String name) {
        for (ChangedLine l : g.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.content() != null && containsWord(l.content(), name)) return true;
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        int idx = text.indexOf(word);
        while (idx >= 0) {
            boolean prevOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + word.length();
            boolean nextOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (prevOk && nextOk) return true;
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    /**
     * Tries to attach {@code importLine} to the class-signature group in
     * {@code sameFileGroups} (the group containing a {@code class/interface ... \{}
     * declaration line).  If no such group exists, adds {@code importGroup} to
     * {@code orphans} so it is preserved unchanged.
     */
    private void attachToClassSignatureOrKeep(
            ChangedLine importLine,
            String file,
            List<ChangeGroup> sameFileGroups,
            Set<ChangeGroup> orphans,
            ChangeGroup importGroup) {

        Optional<ChangeGroup> classGroup = sameFileGroups.stream()
                .filter(g -> g.changedLines().stream().anyMatch(ChangeGrouper::isClassSignatureLine))
                .findFirst();

        if (classGroup.isPresent()) {
            classGroup.get().changedLines().add(0, importLine);
            log.debug("Import '{}' attached to class-signature group {} in {}",
                    importLine.content(), classGroup.get().id(), file);
        } else {
            orphans.add(importGroup);
        }
    }

    private static boolean isClassSignatureLine(ChangedLine l) {
        String c = l.content();
        if (c == null) return false;
        String s = c.strip();
        return (s.contains("class ") || s.contains("interface ")) && s.endsWith("{");
    }

    // -----------------------------------------------------------------------
    // Phase 3: cross-file AST merge via Union-Find
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
            if (cluster.size() == 1) { result.add(cluster.getFirst()); continue; }
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
            if (l.lineNumber() > 0) changedLines.add(l.lineNumber());
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

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private int effectiveLine(ChangedLine l) {
        return l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
    }

    // Union-Find
    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
