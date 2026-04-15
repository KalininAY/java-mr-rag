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
 *       body are grouped together.  Adjacent out-of-method lines (gap &le;
 *       {@value #MAX_LINE_GAP_IN_BLOCK}) are merged into one group so that
 *       Javadoc comment lines and their annotated element end up together.
 *       A Javadoc block ({@code /** ... *&#47;}) or annotation line that immediately
 *       precedes a method declaration (gap &le; {@value #MAX_JAVADOC_GAP} from the
 *       last <em>non-blank</em> line of the run) is moved into that method's bucket
 *       rather than treated as out-of-method.
 *       Lines outside any method are bucketed by the single top-level
 *       CLASS/INTERFACE of the file; if no unique top-level type exists, adjacent
 *       lines share a group, and isolated lines each get their own group.</li>
 *   <li><b>Import attachment</b> – ADD/DELETE {@code import} lines are attached to
 *       every same-file group whose non-CONTEXT lines reference the imported simple
 *       name.  Unmatched imports fall back to the class-signature group; otherwise
 *       they become a standalone group flagged
 *       {@link ChangeGroupFlag#SUSPICIOUS_UNUSED_IMPORT}.
 *       Imports are inserted at the position matching their line number rather than
 *       prepended blindly, so the final diff output stays sorted.</li>
 *   <li><b>Annotation-semantic split</b> – if a single method bucket mixes
 *       lines adding structurally-different annotations (e.g. {@code @Execution}
 *       vs {@code @JiraTest}) they are split into per-annotation sub-groups.
 *       Annotations from <em>different</em> methods are never conflated by this
 *       phase — they already live in separate buckets from Phase 1.
 *       Without a graph, any bucket whose annotation lines have a gap &gt;
 *       {@value #MAX_LINE_GAP_IN_BLOCK} is skipped (multi-method indicator).</li>
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
 *       to {@link #MAX_CROSS_FILE_CLUSTER_SIZE}.  If a file appears in more than
 *       one raw cluster, those clusters are merged transitively before the size
 *       cap is applied.</li>
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
     * Maximum gap (in lines) between the last <em>non-blank</em> line of a
     * Javadoc/annotation run and the first line of the method it documents,
     * for the run to be pulled into that method's bucket.
     */
    private static final int MAX_JAVADOC_GAP = 1;

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

        // Build a map: method startLine -> method endLine, for Javadoc/annotation attachment.
        Map<Integer, Integer> methodStartToEnd = new LinkedHashMap<>();
        if (graph != null) {
            for (GraphNode n : graph.nodes.values()) {
                if (n.kind() == NodeKind.METHOD && file.equals(n.filePath())) {
                    methodStartToEnd.put(n.startLine(), n.endLine());
                }
            }
        }

        Map<Integer, List<ChangedLine>> methodBuckets = new LinkedHashMap<>();
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
                methodBuckets.computeIfAbsent(topLevelKey, k -> new ArrayList<>()).add(line);
            } else {
                outOfMethodLines.add(line);
            }
        }

        // Re-classify Javadoc/annotation out-of-method lines into the method bucket they precede.
        //
        // A contiguous run of out-of-method lines that ends within MAX_JAVADOC_GAP lines of a
        // method start is pulled into that method's bucket. The anchor used for the gap calculation
        // is the last *non-blank* line of the run — trailing blank change lines must not push the
        // anchor past the method declaration they document.
        //
        // This handles two cases:
        //   (a) Javadoc blocks: /** ... */ immediately before a method
        //   (b) Annotation lines (@Foo, @Bar) above a method that weren't caught by
        //       findContainingMethod (annotations are outside the method body in the AST)
        if (!outOfMethodLines.isEmpty() && !methodStartToEnd.isEmpty()) {
            List<ChangedLine> remaining = new ArrayList<>();
            List<ChangedLine> currentRun = new ArrayList<>();
            int runEnd = Integer.MIN_VALUE;          // last line of current run (including blanks)
            int runEndNonBlank = Integer.MIN_VALUE;  // last non-blank line of current run

            for (ChangedLine l : outOfMethodLines) {
                int ln = effectiveLine(l);
                // Continue current run or flush and start a new one
                if (!currentRun.isEmpty() && ln - runEnd > MAX_LINE_GAP_IN_BLOCK) {
                    flushRun(currentRun, runEndNonBlank, methodStartToEnd, methodBuckets, remaining, file);
                    currentRun = new ArrayList<>();
                    runEnd = Integer.MIN_VALUE;
                    runEndNonBlank = Integer.MIN_VALUE;
                }
                currentRun.add(l);
                runEnd = ln;
                if (!isBlankContent(l)) runEndNonBlank = ln;
            }
            // Flush last run
            if (!currentRun.isEmpty()) {
                flushRun(currentRun, runEndNonBlank, methodStartToEnd, methodBuckets, remaining, file);
            }
            outOfMethodLines = remaining;
        }

        List<ChangeGroup> groups = new ArrayList<>();

        for (List<ChangedLine> bucket : methodBuckets.values()) {
            if (bucket.stream().allMatch(l -> l.type() == ChangedLine.LineType.CONTEXT)) continue;
            // Sort bucket lines by effective line number before creating the group
            bucket.sort(Comparator.comparingInt(this::effectiveLine));
            groups.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(), file,
                    new ArrayList<>(bucket), new ArrayList<>()));
        }

        if (!outOfMethodLines.isEmpty()) {
            groups.addAll(mergeAdjacentOutOfMethod(file, outOfMethodLines));
        }

        return groups;
    }

    /**
     * Attempts to attach a completed run of out-of-method lines to the nearest method
     * that starts within {@value #MAX_JAVADOC_GAP} lines after the run's last non-blank
     * line. If no such method exists the run is appended to {@code remaining}.
     */
    private void flushRun(List<ChangedLine> run,
                           int runEndNonBlank,
                           Map<Integer, Integer> methodStartToEnd,
                           Map<Integer, List<ChangedLine>> methodBuckets,
                           List<ChangedLine> remaining,
                           String file) {
        Integer target = findNearestMethodAfter(runEndNonBlank, methodStartToEnd);
        if (target != null) {
            List<ChangedLine> bucket = methodBuckets.computeIfAbsent(target, k -> new ArrayList<>());
            // Prepend: run comes before the method lines already in the bucket
            bucket.addAll(0, run);
            log.debug("Run (lastNonBlank={}) attached to method @{} in {}", runEndNonBlank, target, file);
        } else {
            remaining.addAll(run);
        }
    }

    /**
     * Finds the nearest method whose {@code startLine} is within
     * {@value #MAX_JAVADOC_GAP} lines after {@code lastNonBlankLine}.
     * Returns {@code null} if no such method exists.
     */
    private Integer findNearestMethodAfter(int lastNonBlankLine,
                                            Map<Integer, Integer> methodStartToEnd) {
        if (lastNonBlankLine == Integer.MIN_VALUE) return null;
        Integer best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int start : methodStartToEnd.keySet()) {
            int dist = start - lastNonBlankLine;
            if (dist >= 0 && dist <= MAX_JAVADOC_GAP && dist < bestDist) {
                bestDist = dist;
                best = start;
            }
        }
        return best;
    }

    /** Returns {@code true} if the changed line has no meaningful content (blank diff line). */
    private static boolean isBlankContent(ChangedLine l) {
        String c = l.content();
        return c == null || c.isBlank();
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
                if (currentRun.stream().anyMatch(x -> x.type() != ChangedLine.LineType.CONTEXT)) {
                    result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(), file,
                            new ArrayList<>(currentRun), new ArrayList<>()));
                }
                currentRun = new ArrayList<>();
            }
            currentRun.add(l);
            lastLine = ln;
        }
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
                        insertImportSorted(target.changedLines(), importLine);
                        log.debug("Import '{}' attached to group {} in {}", name, target.id(), file);
                    }
                    continue;
                }
                log.debug("Import '{}' in {} matched no group — trying class-signature fallback", name, file);
            }

            Optional<ChangeGroup> classGroup = sameFileGroups.stream()
                    .filter(g -> g.changedLines().stream().anyMatch(ChangeGrouper::isClassSignatureLine))
                    .findFirst();
            if (classGroup.isPresent()) {
                insertImportSorted(classGroup.get().changedLines(), importLine);
                log.debug("Import '{}' attached to class-signature group {} in {}",
                        importLine.content(), classGroup.get().id(), file);
                continue;
            }

            orphansByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(importLine);
            log.warn("Import '{}' in {} has no referencing group — will be flagged SUSPICIOUS_UNUSED_IMPORT",
                    importLine.content(), file);
        }

        for (var entry : orphansByFile.entrySet()) {
            String id = "G" + groupCounter.incrementAndGet();
            result.add(new ChangeGroup(id, entry.getKey(), entry.getValue(), new ArrayList<>(),
                    EnumSet.of(ChangeGroupFlag.SUSPICIOUS_UNUSED_IMPORT)));
        }
        return result;
    }

    /**
     * Inserts {@code importLine} into {@code lines} at the position that preserves
     * ascending effective-line order, rather than always prepending at index 0.
     */
    private void insertImportSorted(List<ChangedLine> lines, ChangedLine importLine) {
        int importLineNo = effectiveLine(importLine);
        int insertIdx = lines.size(); // default: append
        for (int i = 0; i < lines.size(); i++) {
            if (effectiveLine(lines.get(i)) > importLineNo) {
                insertIdx = i;
                break;
            }
        }
        lines.add(insertIdx, importLine);
    }

    // -----------------------------------------------------------------------
    // Phase 2 (annotation split): split buckets that mix unrelated annotations
    // -----------------------------------------------------------------------

    /**
     * Splits a group whose non-CONTEXT lines contain two or more distinct annotation
     * roots (e.g. {@code @Execution} vs {@code @JiraTest}) into per-root sub-groups.
     *
     * <p><b>Important constraint:</b> annotation lines from different method bodies
     * are <em>never</em> conflated here — they already live in separate Phase-1
     * buckets.  This phase only splits within a single-method bucket where the
     * diff adds annotations belonging to semantically different concerns.
     * A group is considered "multi-method" and therefore skipped when any gap between
     * consecutive annotation lines exceeds {@value #MAX_LINE_GAP_IN_BLOCK} lines.
     */
    private List<ChangeGroup> splitAnnotationBuckets(List<ChangeGroup> groups) {
        List<ChangeGroup> result = new ArrayList<>();
        for (ChangeGroup g : groups) {
            result.addAll(trySplitAnnotations(g));
        }
        return result;
    }

    private List<ChangeGroup> trySplitAnnotations(ChangeGroup g) {
        // Collect non-CONTEXT annotation lines
        List<ChangedLine> annLines = new ArrayList<>();
        for (ChangedLine l : g.changedLines()) {
            if (l.type() != ChangedLine.LineType.CONTEXT && extractAnnotationRoot(l.content()) != null) {
                annLines.add(l);
            }
        }
        if (annLines.isEmpty()) return List.of(g);

        // Guard: skip if annotation lines are non-contiguous (multi-method bucket).
        // Any gap > MAX_LINE_GAP_IN_BLOCK between consecutive annotation lines
        // means they belong to different methods — do not split.
        annLines.sort(Comparator.comparingInt(this::effectiveLine));
        for (int i = 1; i < annLines.size(); i++) {
            int gap = effectiveLine(annLines.get(i)) - effectiveLine(annLines.get(i - 1));
            if (gap > MAX_LINE_GAP_IN_BLOCK) {
                log.debug("Skipping annotation-split for group {} (non-contiguous annotations, gap={})",
                        g.id(), gap);
                return List.of(g);
            }
        }

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

        if (byAnnotation.size() < 2) return List.of(g);

        log.debug("Splitting group {} by annotation roots: {}", g.id(), byAnnotation.keySet());
        List<ChangeGroup> result = new ArrayList<>();

        if (nonAnnotation.stream().anyMatch(l -> l.type() != ChangedLine.LineType.CONTEXT)) {
            result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(),
                    g.primaryFile(), new ArrayList<>(nonAnnotation), new ArrayList<>()));
        }
        for (List<ChangedLine> annotationLines : byAnnotation.values()) {
            result.add(ChangeGroup.of("G" + groupCounter.incrementAndGet(),
                    g.primaryFile(), new ArrayList<>(annotationLines), new ArrayList<>()));
        }
        return result;
    }

    private static String extractAnnotationRoot(String content) {
        if (content == null) return null;
        String s = content.strip();
        if (!s.startsWith("@")) return null;
        int end = 1;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) end++;
        return end > 1 ? s.substring(1, end) : null;
    }

    // -----------------------------------------------------------------------
    // Phase 2.5: rule-based version / changelog merge
    // -----------------------------------------------------------------------

    private List<ChangeGroup> versionChangelogMerge(List<ChangeGroup> groups) {
        Map<String, ChangeGroup> versionByTag  = new LinkedHashMap<>();
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

        for (ChangeGroup g : groups) {
            if (!merged.contains(g)) result.add(g);
        }
        return result;
    }

    private static String baseFileName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // -----------------------------------------------------------------------
    // Phase 3a: intra-file def/call linkage
    // -----------------------------------------------------------------------

    private List<ChangeGroup> intraFileDefCallMerge(List<ChangeGroup> groups, ProjectGraph graph) {
        if (graph == null || groups.size() <= 1) return groups;

        int n = groups.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        List<Set<String>> declaredMethods = new ArrayList<>();
        List<Set<String>> calledMethods   = new ArrayList<>();
        for (ChangeGroup g : groups) {
            declaredMethods.add(declaredMethodNames(g, graph));
            calledMethods.add(calledMethodNames(g, graph));
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!groups.get(i).primaryFile().equals(groups.get(j).primaryFile())) continue;
                if (!Collections.disjoint(declaredMethods.get(i), calledMethods.get(j))
                        || !Collections.disjoint(declaredMethods.get(j), calledMethods.get(i))) {
                    log.debug("Intra-file def/call link: groups {} and {} (file {})",
                            groups.get(i).id(), groups.get(j).id(), groups.get(i).primaryFile());
                    union(parent, i, j);
                }
            }
        }

        return buildClusters(groups, parent);
    }

    private Set<String> declaredMethodNames(ChangeGroup g, ProjectGraph graph) {
        Set<Integer> changedLineNos = changedLineNumbers(g);
        Set<String> names = new HashSet<>();
        for (GraphNode n : graph.nodes.values()) {
            if (n.kind() != NodeKind.METHOD) continue;
            if (!g.primaryFile().equals(n.filePath())) continue;
            if (changedLineNos.contains(n.startLine())) names.add(simpleMethodName(n.id()));
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

    private static String simpleMethodName(String qualifiedId) {
        if (qualifiedId == null) return "";
        int hash = qualifiedId.lastIndexOf('#');
        String name = hash >= 0 ? qualifiedId.substring(hash + 1) : qualifiedId;
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

        // --- Transitive file-overlap merge ---
        // If the same file appears in two different raw clusters, those clusters
        // must be merged transitively (e.g. JiraProvider.java shared by G17 & G18).
        Map<Integer, List<Integer>> rawClusterIndices = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            rawClusterIndices.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        // Map file -> list of cluster roots that contain a group from this file
        Map<String, List<Integer>> fileToRoots = new LinkedHashMap<>();
        for (var entry : rawClusterIndices.entrySet()) {
            int root = entry.getKey();
            for (int idx : entry.getValue()) {
                fileToRoots.computeIfAbsent(groups.get(idx).primaryFile(), k -> new ArrayList<>()).add(root);
            }
        }
        // Merge clusters that share a file
        for (List<Integer> roots : fileToRoots.values()) {
            if (roots.size() < 2) continue;
            log.debug("Transitive file-overlap merge: cluster roots {} share a file", roots);
            for (int k = 1; k < roots.size(); k++) {
                union(parent, roots.get(0), roots.get(k));
            }
        }

        // Collect final clusters after transitive merge
        Map<Integer, List<ChangeGroup>> finalClusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            finalClusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(groups.get(i));
        }

        List<ChangeGroup> result = new ArrayList<>();
        for (List<ChangeGroup> cluster : finalClusters.values()) {
            if (cluster.size() == 1) {
                result.add(cluster.getFirst());
                continue;
            }
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

    private List<List<ChangeGroup>> splitOversizedCluster(
            List<ChangeGroup> cluster,
            List<Set<String>> allKeys,
            List<ChangeGroup> allGroups) {

        if (cluster.size() <= MAX_CROSS_FILE_CLUSTER_SIZE) return List.of(cluster);

        log.debug("Cluster size {} exceeds MAX_CROSS_FILE_CLUSTER_SIZE={}, splitting greedily",
                cluster.size(), MAX_CROSS_FILE_CLUSTER_SIZE);

        List<Set<String>> clusterKeys = new ArrayList<>();
        for (ChangeGroup g : cluster) {
            int idx = allGroups.indexOf(g);
            clusterKeys.add(idx >= 0 ? allKeys.get(idx) : Set.of());
        }

        boolean[] assigned = new boolean[cluster.size()];
        List<List<ChangeGroup>> result = new ArrayList<>();

        for (int i = 0; i < cluster.size(); i++) {
            if (assigned[i]) continue;
            List<ChangeGroup> sub = new ArrayList<>();
            sub.add(cluster.get(i));
            assigned[i] = true;

            while (sub.size() < MAX_CROSS_FILE_CLUSTER_SIZE) {
                int bestJ = -1;
                int bestScore = 0;
                Set<String> subKeys = unionKeys(sub, cluster, clusterKeys);
                for (int j = i + 1; j < cluster.size(); j++) {
                    if (assigned[j]) continue;
                    int score = sharedCount(subKeys, clusterKeys.get(j));
                    if (score > bestScore) { bestScore = score; bestJ = j; }
                }
                if (bestJ < 0 || bestScore == 0) break;
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

    private List<ChangeGroup> buildClusters(List<ChangeGroup> groups, int[] parent) {
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
        // Sort merged lines by effective line number so the rendered diff is readable.
        allLines.sort(Comparator.comparingInt(this::effectiveLine));
        EnumSet<ChangeGroupFlag> mergedFlags = EnumSet.noneOf(ChangeGroupFlag.class);
        for (ChangeGroup g : cluster) mergedFlags.addAll(g.flags());
        return new ChangeGroup(id, primary, allLines, new ArrayList<>(), mergedFlags);
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static boolean isImportLine(ChangedLine l) {
        String c = l.content();
        return c != null && c.strip().startsWith("import ");
    }

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
