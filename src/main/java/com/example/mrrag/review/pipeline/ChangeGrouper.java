package com.example.mrrag.review.pipeline;

import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.model.ChangeGroupFlag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Groups changed lines into cohesive ChangeGroups.
 *
 * <p>Grouping pipeline:
 * <ol>
 *   <li><b>Code-block grouping (intra-file)</b> – import lines are excluded from
 *       this phase entirely (collected separately).  Lines inside the same method
 *       body are grouped together.  Adjacent out-of-method lines (gap ≤
 *       {@value #MAX_LINE_GAP_IN_BLOCK}) are merged into one group so that
 *       Javadoc comment lines and their annotated element end up together.
 *       Lines outside any method are bucketed by the single top-level
 *       CLASS/INTERFACE of the file; if no unique top-level type exists, adjacent
 *       lines share a group, and isolated lines each get their own group.</li>
 *   <li><b>Import attachment</b> – ADD/DELETE {@code import} lines are attached to
 *       every same-file group whose non-CONTEXT lines reference the imported simple
 *       name.  Unmatched imports fall back to the class-signature group; otherwise
 *       they become a standalone group flagged
 *       {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}.</li>
 *   <li><b>Annotation-semantic split</b> – if a bucket produced by Phase 1 mixes
 *       lines adding structurally-different annotations (e.g. {@code @Execution}
 *       vs {@code @JiraTest}) they are split into per-annotation sub-groups so
 *       that unrelated concerns are not conflated.</li>
 *   <li><b>Rule-based version/changelog merge (Phase 2.5)</b> – if the diff
 *       touches both a build-descriptor version line and a changelog header line
 *       for the same version string, those groups are merged and flagged
 *       {@link ChangeGroupFlag#VERSION_CHANGELOG_PAIR}.</li>
 *   <li><b>Intra-file def/call linkage (Phase 3a)</b> – within the same file,
 *       a group that declares a method {@code foo()} and a group that calls
 *       {@code foo()} are merged so that definition and usage are reviewed
 *       together.</li>
 *   <li><b>Cross-file AST merge (Phase 3b)</b> – groups from different files are
 *       merged when they share resolved symbol references via Union-Find, subject
 *       to {@link #MAX_CROSS_FILE_CLUSTER_SIZE}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeGrouper {

    private final GraphQueryService graphQuery;
    private final AtomicInteger groupCounter = new AtomicInteger(0);

    /** Maximum line-number gap between adjacent out-of-method lines to be merged. */
    private static final int MAX_LINE_GAP_IN_BLOCK = 2;

    /**
     * Maximum number of distinct files allowed in a single cross-file cluster.
     * Clusters that exceed this limit are split greedily by shared-key count.
     */
    static final int MAX_CROSS_FILE_CLUSTER_SIZE = 3;

    private static final Pattern VERSION_IN_BUILD =
            Pattern.compile("version\\s+['\"]?(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern VERSION_IN_CHANGELOG =
            Pattern.compile("^ver(?:sion)?\\s+(\\d+\\.\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CHANGELOG_FILE_NAMES =
            Set.of("changelog", "changes", "history", "releasenotes", "release_notes");
    private static final Set<String> BUILD_FILE_NAMES =
            Set.of("build.gradle", "pom.xml", "build.gradle.kts");

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
        List<ChangedLine> importLines = new ArrayList<>();
        List<ChangedLine> codeLines  = new ArrayList<>();
        for (ChangedLine l : lines) {
            if (isImportLine(l) && l.type() != ChangedLine.LineType.CONTEXT) {
                importLines.add(l);
            } else {
                codeLines.add(l);
            }
        }

        List<ChangeGroup> phase1 = codeBlockGroup(codeLines, graph);
        log.debug("Phase 1 (code-block): {} groups", phase1.size());

        List<ChangeGroup> phase2 = attachImports(phase1, importLines);
        log.debug("Phase 2 (import-attach): {} groups", phase2.size());

        List<ChangeGroup> phase2ann = splitAnnotationBuckets(phase2);
        log.debug("Phase 2 (annotation-split): {} groups", phase2ann.size());

        List<ChangeGroup> phase25 = versionChangelogMerge(phase2ann);
        log.debug("Phase 2.5 (version-changelog): {} groups", phase25.size());

        List<ChangeGroup> phase3a = intraFileDefCallMerge(phase25, graph);
        log.debug("Phase 3a (intra-file def/call): {} groups", phase3a.size());

        List<ChangeGroup> phase3b = semanticMerge(phase3a, graph);
        log.debug("Phase 3b (cross-file AST): {} groups", phase3b.size());

        return phase3b;
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
        // Sort lines by effective line number so adjacency detection is reliable.
        List<ChangedLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(this::effectiveLine));

        final Integer topLevelKey;
        if (graph != null) {
            topLevelKey = graphQuery.findTopLevelType(graph, file)
                    .map(GraphNode::startLine)
                    .orElse(null);
        } else {
            topLevelKey = null;
        }

        // out-of-method lines: group adjacent ones together (gap <= MAX_LINE_GAP_IN_BLOCK).
        // We collect them first, then merge adjacent runs before creating ChangeGroups.
        Map<Integer, List<ChangedLine>> methodBuckets = new LinkedHashMap<>(); // key = method startLine
        List<ChangedLine> outOfMethodLines = new ArrayList<>();

        for (ChangedLine line : sorted) {
            int lineNo = effectiveLine(line);
            if (graph != null && lineNo > 0) {
                Optional<GraphNode> method = graphQuery.findContainingMethod(graph, file, lineNo);
                if (method.isPresent()) {
                    methodBuckets.computeIfAbsent(method.get().startLine(), k -> new ArrayList<>()).add(line);
                    continue;
                }
            }
            // Falls through to out-of-method
            if (topLevelKey != null) {
                // All share the top-level class bucket
                methodBuckets.computeIfAbsent(topLevelKey, k -> new ArrayList<>()).add(line);
            } else {
                outOfMethodLines.add(line);
            }
        }

        List<ChangeGroup> groups = new ArrayList<>();

        // Emit method-bucket groups
        for (List<ChangedLine> bucket : methodBuckets.values()) {
            if (bucket.stream().allMatch(l -> l.type() == ChangedLine.LineType.CONTEXT)) continue;
            groups.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(), file,
                    new ArrayList<>(bucket), new ArrayList<>()));
        }

        // Merge adjacent out-of-method lines into runs (fix for G6-G9 javadoc fragmentation)
        if (!outOfMethodLines.isEmpty()) {
            groups.addAll(mergeAdjacentOutOfMethod(file, outOfMethodLines));
        }

        return groups;
    }

    /**
     * Merges a pre-sorted list of out-of-method lines into contiguous runs.
     * Two lines belong to the same run when their effective line numbers differ
     * by at most {@value #MAX_LINE_GAP_IN_BLOCK}.
     */
    private List<ChangeGroup> mergeAdjacentOutOfMethod(String file, List<ChangedLine> lines) {
        List<ChangeGroup> result = new ArrayList<>();
        List<ChangedLine> currentRun = new ArrayList<>();
        int lastLine = Integer.MIN_VALUE;

        for (ChangedLine l : lines) {
            int ln = effectiveLine(l);
            if (!currentRun.isEmpty() && ln - lastLine > MAX_LINE_GAP_IN_BLOCK) {
                // Flush current run
                if (currentRun.stream().anyMatch(x -> x.type() != ChangedLine.LineType.CONTEXT)) {
                    result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(), file,
                            new ArrayList<>(currentRun), new ArrayList<>()));
                }
                currentRun = new ArrayList<>();
            }
            currentRun.add(l);
            lastLine = ln;
        }
        // Flush last run
        if (!currentRun.isEmpty()
                && currentRun.stream().anyMatch(x -> x.type() != ChangedLine.LineType.CONTEXT)) {
            result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(), file,
                    new ArrayList<>(currentRun), new ArrayList<>()));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Phase 2: attach import lines to the groups that reference the imported type
    // -----------------------------------------------------------------------

    /**
     * Attaches each ADD/DELETE import line to every same-file group that references
     * the imported simple name in a non-CONTEXT line.
     *
     * <p>Fallback chain when no referencing group is found:
     * <ol>
     *   <li>Attach to the class-signature group of the same file.</li>
     *   <li>If no class-signature group exists either, emit as a standalone group
     *       flagged {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}.</li>
     * </ol>
     */
    private List<ChangeGroup> attachImports(List<ChangeGroup> groups, List<ChangedLine> importLines) {
        if (importLines.isEmpty()) return groups;

        List<ChangeGroup> result = new ArrayList<>(groups);

        Map<String, List<ChangeGroup>> byFile = new LinkedHashMap<>();
        for (ChangeGroup g : result) {
            byFile.computeIfAbsent(g.primaryFile(), k -> new ArrayList<>()).add(g);
        }

        Map<String, List<ChangedLine>> orphansByFile = new LinkedHashMap<>();

        for (ChangedLine importLine : importLines) {
            String file = importLine.filePath();
            List<ChangeGroup> sameFileGroups = byFile.getOrDefault(file, List.of());

            Optional<String> simpleName = GraphQueryService.resolveImportSimpleName(importLine.content());

            if (simpleName.isPresent()) {
                String name = simpleName.get();
                List<ChangeGroup> targets = sameFileGroups.stream()
                        .filter(g -> groupReferencesName(g, name))
                        .toList();
                if (!targets.isEmpty()) {
                    for (ChangeGroup target : targets) {
                        target.changedLines().add(0, importLine);
                        log.debug("Import '{}' attached to group {} in {}", name, target.id(), file);
                    }
                    continue;
                }
                log.debug("Import '{}' in {} matched no group — trying class-signature fallback", name, file);
            }

            // Fallback 1: class-signature group
            Optional<ChangeGroup> classGroup = sameFileGroups.stream()
                    .filter(g -> g.changedLines().stream().anyMatch(ChangeGrouper::isClassSignatureLine))
                    .findFirst();
            if (classGroup.isPresent()) {
                classGroup.get().changedLines().add(0, importLine);
                log.debug("Import '{}' attached to class-signature group {} in {}",
                        importLine.content(), classGroup.get().id(), file);
                continue;
            }

            // Fallback 2: orphan — standalone group with flag
            orphansByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(importLine);
            log.warn("Import '{}' in {} has no referencing group — will be flagged SUSPICIOUS_UNUSED_IMPORT",
                    importLine.content(), file);
        }

        // Emit orphan imports as standalone flagged groups (one group per file)
        for (var entry : orphansByFile.entrySet()) {
            String id = "G" + groupCounter.incrementAndGet();
            result.add(new ChangeGroup(id, entry.getKey(), entry.getValue(), new ArrayList<>(),
                    EnumSet.of(ChangeGroupFlag.SUSPICIOUS_UNUSED_IMPORT)));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Phase 2 (annotation split): split buckets that mix unrelated annotations
    // -----------------------------------------------------------------------

    /**
     * Splits a group whose changed lines contain additions of structurally
     * different annotations into per-annotation-root sub-groups.
     *
     * <p>A line is considered an "annotation line" when its stripped content
     * starts with {@code @} (after removing leading +/-).  Lines with the same
     * annotation root (first token after {@code @}) are kept together; lines
     * that are not annotation lines stay in the original group.  Only groups
     * with ≥ 2 distinct annotation roots are split.
     */
    private List<ChangeGroup> splitAnnotationBuckets(List<ChangeGroup> groups) {
        List<ChangeGroup> result = new ArrayList<>();
        for (ChangeGroup g : groups) {
            List<ChangeGroup> split = trySplitAnnotations(g);
            result.addAll(split);
        }
        return result;
    }

    private List<ChangeGroup> trySplitAnnotations(ChangeGroup g) {
        // Collect non-CONTEXT annotation lines and their roots
        Map<String, List<ChangedLine>> byAnnotation = new LinkedHashMap<>();
        List<ChangedLine> nonAnnotation = new ArrayList<>();

        for (ChangedLine l : g.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) {
                nonAnnotation.add(l);
                continue;
            }
            String ann = extractAnnotationRoot(l.content());
            if (ann != null) {
                byAnnotation.computeIfAbsent(ann, k -> new ArrayList<>()).add(l);
            } else {
                nonAnnotation.add(l);
            }
        }

        // Only split when there are 2+ distinct annotation roots
        if (byAnnotation.size() < 2) return List.of(g);

        log.debug("Splitting group {} by annotation roots: {}", g.id(), byAnnotation.keySet());
        List<ChangeGroup> result = new ArrayList<>();

        // Non-annotation lines stay in a group of their own (if non-empty and non-all-CONTEXT)
        if (nonAnnotation.stream().anyMatch(l -> l.type() != ChangedLine.LineType.CONTEXT)) {
            result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(),
                    g.primaryFile(), new ArrayList<>(nonAnnotation), new ArrayList<>()));
        }

        for (List<ChangedLine> annLines : byAnnotation.values()) {
            result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(),
                    g.primaryFile(), new ArrayList<>(annLines), new ArrayList<>()));
        }
        return result;
    }

    /**
     * Returns the annotation root name (e.g. {@code "Execution"} for
     * {@code "    @Execution(ExecutionMode.CONCURRENT)"}) or {@code null} if
     * the line is not an annotation line.
     */
    private static String extractAnnotationRoot(String content) {
        if (content == null) return null;
        String s = content.strip();
        if (!s.startsWith("@")) return null;
        // Take everything between @ and the first non-identifier char
        int end = 1;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) end++;
        return end > 1 ? s.substring(1, end) : null;
    }

    // -----------------------------------------------------------------------
    // Phase 2.5: rule-based version / changelog merge
    // -----------------------------------------------------------------------

    /**
     * Merges a build-descriptor version group with a changelog header group when
     * they reference the same version string.  The merged group is flagged
     * {@link ChangeGroupFlag#VERSION_CHANGELOG_PAIR}.
     */
    private List<ChangeGroup> versionChangelogMerge(List<ChangeGroup> groups) {
        // Collect candidate groups by role
        Map<String, ChangeGroup> versionByTag  = new LinkedHashMap<>(); // version string -> group
        Map<String, ChangeGroup> changelogByTag = new LinkedHashMap<>();

        for (ChangeGroup g : groups) {
            String fileName = baseFileName(g.primaryFile());
            for (ChangedLine l : g.changedLines()) {
                if (l.type() == ChangedLine.LineType.CONTEXT) continue;
                String content = l.content() == null ? "" : l.content();
                if (BUILD_FILE_NAMES.contains(fileName)) {
                    var m = VERSION_IN_BUILD.matcher(content);
                    if (m.find()) { versionByTag.putIfAbsent(m.group(1), g); break; }
                } else if (CHANGELOG_FILE_NAMES.contains(fileName.toLowerCase())) {
                    var m = VERSION_IN_CHANGELOG.matcher(content.strip());
                    if (m.find()) { changelogByTag.putIfAbsent(m.group(1), g); break; }
                }
            }
        }

        // Find matching pairs
        Set<ChangeGroup> merged = new HashSet<>();
        List<ChangeGroup> result = new ArrayList<>();

        for (var entry : versionByTag.entrySet()) {
            String ver = entry.getKey();
            ChangeGroup buildGroup = entry.getValue();
            ChangeGroup clGroup   = changelogByTag.get(ver);
            if (clGroup == null || buildGroup == clGroup) continue;

            log.debug("Version/changelog merge: version={}, files=[{}, {}]",
                    ver, buildGroup.primaryFile(), clGroup.primaryFile());

            String id = "G" + groupCounter.incrementAndGet();
            List<ChangedLine> allLines = new ArrayList<>();
            allLines.addAll(buildGroup.changedLines());
            allLines.addAll(clGroup.changedLines());
            result.add(new ChangeGroup(id, buildGroup.primaryFile(), allLines, new ArrayList<>(),
                    EnumSet.of(ChangeGroupFlag.VERSION_CHANGELOG_PAIR)));
            merged.add(buildGroup);
            merged.add(clGroup);
        }

        // Pass through all non-merged groups
        for (ChangeGroup g : groups) {
            if (!merged.contains(g)) result.add(g);
        }
        return result;
    }

    /** Extracts the bare file name (last path segment) from a relative path. */
    private static String baseFileName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // -----------------------------------------------------------------------
    // Phase 3a: intra-file def/call linkage
    // -----------------------------------------------------------------------

    /**
     * Within the same file, merges a group that <em>declares</em> a method with
     * the group that <em>calls</em> that method, so that definition and first usage
     * are always reviewed together.
     *
     * <p>Detection: a group is a "declaration group" for method {@code foo} when
     * the AST graph contains a METHOD node whose {@code id} ends with {@code #foo}
     * and whose {@code startLine} is one of the group's changed lines.  A group
     * is a "call group" for {@code foo} when the graph contains an outgoing CALL
     * edge whose {@code callee} ends with {@code #foo} and whose {@code line} is
     * one of the group's changed lines.
     */
    private List<ChangeGroup> intraFileDefCallMerge(List<ChangeGroup> groups, ProjectGraph graph) {
        if (graph == null || groups.size() <= 1) return groups;

        int n = groups.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // Build per-group sets of declared method simple-names and called method simple-names
        List<Set<String>> declaredMethods = new ArrayList<>();
        List<Set<String>> calledMethods   = new ArrayList<>();
        for (ChangeGroup g : groups) {
            declaredMethods.add(declaredMethodNames(g, graph));
            calledMethods.add(calledMethodNames(g, graph));
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                // i declares something that j calls, or vice-versa
                if (!Collections.disjoint(declaredMethods.get(i), calledMethods.get(j))
                        || !Collections.disjoint(declaredMethods.get(j), calledMethods.get(i))) {
                    log.debug("Intra-file def/call link: groups {} and {} (file {})",
                            groups.get(i).id(), groups.get(j).id(), groups.get(i).primaryFile());
                    union(parent, i, j);
                }
            }
        }

        return buildClusters(groups, parent, /* crossFileOnly= */ false);
    }

    private Set<String> declaredMethodNames(ChangeGroup g, ProjectGraph graph) {
        Set<Integer> changedLineNos = changedLineNumbers(g);
        Set<String> names = new HashSet<>();
        for (GraphNode n : graph.nodes.values()) {
            if (n.kind() != NodeKind.METHOD) continue;
            if (!g.primaryFile().equals(n.filePath())) continue;
            if (changedLineNos.contains(n.startLine())) {
                names.add(simpleMethodName(n.id()));
            }
        }
        return names;
    }

    private Set<String> calledMethodNames(ChangeGroup g, ProjectGraph graph) {
        Set<Integer> changedLineNos = changedLineNumbers(g);
        Set<String> names = new HashSet<>();
        for (List<com.example.mrrag.graph.model.GraphEdge> edges : graph.edgesFrom.values()) {
            for (com.example.mrrag.graph.model.GraphEdge e : edges) {
                if (!g.primaryFile().equals(e.filePath())) continue;
                if (changedLineNos.contains(e.line()) && e.callee() != null) {
                    names.add(simpleMethodName(e.callee()));
                }
            }
        }
        return names;
    }

    private Set<Integer> changedLineNumbers(ChangeGroup g) {
        Set<Integer> set = new HashSet<>();
        for (ChangedLine l : g.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            if (l.lineNumber() > 0)    set.add(l.lineNumber());
            if (l.oldLineNumber() > 0) set.add(l.oldLineNumber());
        }
        return set;
    }

    /** Extracts the simple method name from a qualified id like {@code pkg.Class#method}. */
    private static String simpleMethodName(String qualifiedId) {
        if (qualifiedId == null) return "";
        int hash = qualifiedId.lastIndexOf('#');
        String name = hash >= 0 ? qualifiedId.substring(hash + 1) : qualifiedId;
        // Strip parameter list if present: "method(int,String)" -> "method"
        int paren = name.indexOf('(');
        return paren >= 0 ? name.substring(0, paren) : name;
    }

    // -----------------------------------------------------------------------
    // Phase 3b: cross-file AST merge via Union-Find
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

        // Collect raw clusters
        Map<Integer, List<ChangeGroup>> rawClusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            rawClusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }

        List<ChangeGroup> result = new ArrayList<>();
        for (List<ChangeGroup> cluster : rawClusters.values()) {
            if (cluster.size() == 1) {
                result.add(cluster.getFirst());
                continue;
            }
            // Split oversized clusters greedily
            List<List<ChangeGroup>> subclusters = splitOversizedCluster(cluster, keys, groups);
            for (List<ChangeGroup> sub : subclusters) {
                if (sub.size() == 1) { result.add(sub.getFirst()); continue; }
                log.debug("Cross-file merge: {} groups -> one (files: {})",
                        sub.size(), sub.stream().map(ChangeGroup::primaryFile).toList());
                result.add(mergeCluster(sub));
            }
        }
        return result;
    }

    /**
     * Splits a cluster that exceeds {@link #MAX_CROSS_FILE_CLUSTER_SIZE} into
     * sub-clusters using a greedy shared-key approach: iteratively pair the group
     * with the most shared keys with its closest neighbour until each sub-cluster
     * is within the size limit.
     */
    private List<List<ChangeGroup>> splitOversizedCluster(
            List<ChangeGroup> cluster,
            List<Set<String>> allKeys,
            List<ChangeGroup> allGroups) {

        if (cluster.size() <= MAX_CROSS_FILE_CLUSTER_SIZE) return List.of(cluster);

        log.debug("Cluster size {} exceeds MAX_CROSS_FILE_CLUSTER_SIZE={}, splitting greedily",
                cluster.size(), MAX_CROSS_FILE_CLUSTER_SIZE);

        // Map each group in the cluster back to its index in allGroups for key lookup
        List<Set<String>> clusterKeys = new ArrayList<>();
        for (ChangeGroup g : cluster) {
            int idx = allGroups.indexOf(g);
            clusterKeys.add(idx >= 0 ? allKeys.get(idx) : Set.of());
        }

        // Greedy: build sub-clusters by starting from the first unassigned group
        // and pulling in the neighbours with the highest key overlap
        boolean[] assigned = new boolean[cluster.size()];
        List<List<ChangeGroup>> result = new ArrayList<>();

        for (int i = 0; i < cluster.size(); i++) {
            if (assigned[i]) continue;
            List<ChangeGroup> sub = new ArrayList<>();
            sub.add(cluster.get(i));
            assigned[i] = true;

            // Score remaining unassigned by shared keys with current sub-cluster
            while (sub.size() < MAX_CROSS_FILE_CLUSTER_SIZE) {
                int bestJ = -1;
                int bestScore = 0;
                Set<String> subKeys = unionKeys(sub, cluster, clusterKeys);
                for (int j = i + 1; j < cluster.size(); j++) {
                    if (assigned[j]) continue;
                    int score = sharedCount(subKeys, clusterKeys.get(j));
                    if (score > bestScore) { bestScore = score; bestJ = j; }
                }
                if (bestJ < 0 || bestScore == 0) break; // no more related groups
                sub.add(cluster.get(bestJ));
                assigned[bestJ] = true;
            }
            result.add(sub);
        }
        return result;
    }

    private Set<String> unionKeys(List<ChangeGroup> sub, List<ChangeGroup> cluster,
                                   List<Set<String>> clusterKeys) {
        Set<String> u = new HashSet<>();
        for (ChangeGroup g : sub) {
            int idx = cluster.indexOf(g);
            if (idx >= 0) u.addAll(clusterKeys.get(idx));
        }
        return u;
    }

    private int sharedCount(Set<String> a, Set<String> b) {
        int count = 0;
        for (String k : b) if (a.contains(k)) count++;
        return count;
    }

    private Set<String> buildAstKeys(ChangeGroup group, ProjectGraph graph) {
        Set<Integer> changedLines = changedLineNumbers(group);
        return graphQuery.astKeysForLines(graph, group.primaryFile(), changedLines);
    }

    /**
     * Builds the final list of merged groups from a Union-Find parent array.
     *
     * @param crossFileOnly when {@code true}, only merges groups from different files
     *                      (used by Phase 3b); when {@code false} all same-root
     *                      groups are merged (used by Phase 3a).
     */
    private List<ChangeGroup> buildClusters(List<ChangeGroup> groups, int[] parent,
                                             boolean crossFileOnly) {
        Map<Integer, List<ChangeGroup>> clusters = new LinkedHashMap<>();
        int n = groups.size();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }
        List<ChangeGroup> result = new ArrayList<>();
        for (List<ChangeGroup> cluster : clusters.values()) {
            if (cluster.size() == 1) { result.add(cluster.getFirst()); continue; }
            result.add(mergeCluster(cluster));
        }
        return result;
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
        // Preserve flags from all merged groups
        EnumSet<ChangeGroupFlag> mergedFlags = EnumSet.noneOf(ChangeGroupFlag.class);
        for (ChangeGroup g : cluster) mergedFlags.addAll(g.flags());
        return new ChangeGroup(id, primary, allLines, new ArrayList<>(), mergedFlags);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

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

    private static boolean isClassSignatureLine(ChangedLine l) {
        String c = l.content();
        if (c == null) return false;
        String s = c.strip();
        return (s.contains("class ") || s.contains("interface ")) && s.endsWith("{");
    }

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
