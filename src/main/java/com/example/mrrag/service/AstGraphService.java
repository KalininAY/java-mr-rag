package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Builds a rich symbol graph for a Java project using Spoon.
 *
 * <p><b>Nodes:</b> CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
 * LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE<br>
 * <b>Edges:</b> DECLARES, EXTENDS, IMPLEMENTS, INVOKES, INSTANTIATES,
 * INSTANTIATES_ANONYMOUS, REFERENCES_METHOD, READS_FIELD, WRITES_FIELD,
 * READS_LOCAL_VAR, WRITES_LOCAL_VAR, THROWS, ANNOTATED_WITH,
 * REFERENCES_TYPE, OVERRIDES, HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AstGraphService {

    // ------------------------------------------------------------------
    // Public model
    // ------------------------------------------------------------------

    /** Kinds of graph nodes. */
    public enum NodeKind {
        /** A concrete class, enum, or record type. */
        CLASS,
        /** A Java {@code interface} declaration. */
        INTERFACE,
        /** A constructor ({@code <init>}). */
        CONSTRUCTOR,
        /** An instance or static method. */
        METHOD,
        /** A class field. */
        FIELD,
        /** A local variable or method parameter ({@code CtVariable} that is not a field). */
        VARIABLE,
        /** A lambda expression. */
        LAMBDA,
        /** An annotation type (target of {@code ANNOTATED_WITH} edges). */
        ANNOTATION,
        /**
         * A generic type parameter declaration.
         * Examples: {@code T} in {@code class Foo<T>},
         * {@code K extends Comparable<K>} in a method signature.
         */
        TYPE_PARAM,
        /**
         * An element declared inside an {@code @interface} body.
         * Example: {@code String value() default "";}
         */
        ANNOTATION_ATTRIBUTE
    }

    /**
     * Kinds of directed graph edges.
     * Each value can be toggled via {@code graph.edge.<name>.enabled=true/false}.
     */
    public enum EdgeKind {

        // ── Structural (declaration) ────────────────────────────────────────────────

        /**
         * Owner declares a child member.
         * Examples: CLASS→METHOD, CLASS→FIELD, CLASS→CONSTRUCTOR, METHOD→LAMBDA.
         */
        DECLARES,

        // ── Type hierarchy ─────────────────────────────────────────────────────

        /** {@code class A extends B} → A –EXTENDS→ B */
        EXTENDS,

        /** {@code class A implements I} → A –IMPLEMENTS→ I */
        IMPLEMENTS,

        // ── Invocations ───────────────────────────────────────────────────────

        /** {@code foo.bar()} → caller –INVOKES→ callee */
        INVOKES,

        /** {@code new Foo(arg)} → caller –INSTANTIATES→ Foo */
        INSTANTIATES,

        /** {@code new Runnable() { ... }} → caller –INSTANTIATES_ANONYMOUS→ anon-type */
        INSTANTIATES_ANONYMOUS,

        /** {@code Foo::bar}, {@code Foo::new} → caller –REFERENCES_METHOD→ target */
        REFERENCES_METHOD,

        // ── Field access ────────────────────────────────────────────────────

        /** {@code this.value}, {@code obj.field} */
        READS_FIELD,

        /** {@code this.value = x} */
        WRITES_FIELD,

        // ── Local variable access ──────────────────────────────────────────────────

        /** Caller reads a local variable or method parameter. */
        READS_LOCAL_VAR,

        /** Caller writes a local variable or method parameter. */
        WRITES_LOCAL_VAR,

        // ── Exceptions ────────────────────────────────────────────────────────

        /** Method/constructor contains {@code throw new FooException(...)}. */
        THROWS,

        // ── Annotations ──────────────────────────────────────────────────────

        /** CLASS/INTERFACE/METHOD/FIELD → ANNOTATION type. */
        ANNOTATED_WITH,

        // ── Type references ─────────────────────────────────────────────────────

        /** {@code Foo.class}, {@code instanceof Foo}, cast to {@code Foo}. */
        REFERENCES_TYPE,

        // ── Inheritance ──────────────────────────────────────────────────────

        /** child –OVERRIDES→ parent method */
        OVERRIDES,

        // ── Generics ──────────────────────────────────────────────────────

        /**
         * A type or executable has a generic type parameter.
         * {@code class Foo<T>} → Foo –HAS_TYPE_PARAM→ T
         * {@code <R> R map(...)} → map –HAS_TYPE_PARAM→ R
         */
        HAS_TYPE_PARAM,

        /**
         * A type parameter has an upper bound.
         * {@code T extends Comparable<T>} → T –HAS_BOUND→ Comparable
         */
        HAS_BOUND,

        // ── Annotation attributes ───────────────────────────────────────────────────────

        /**
         * An annotation type declares an attribute element.
         * {@code @interface Foo { String value(); }} → Foo –ANNOTATION_ATTR→ value
         */
        ANNOTATION_ATTR
    }

    /**
     * A node in the symbol graph.
     *
     * @param id                  unique node identifier
     * @param kind                {@link NodeKind} of this node
     * @param simpleName          unqualified name
     * @param filePath            source-relative file path ({@code "unknown"} for external nodes)
     * @param startLine           first source line (1-based); {@code -1} for external/synthetic nodes
     *                            that have no source file in this project
     * @param endLine             last source line (1-based); {@code -1} for external/synthetic nodes
     * @param sourceSnippet       verbatim original source lines for project nodes;
     *                            Spoon pretty-printed text for external/synthetic nodes
     *                            (no source file available); empty string when unavailable
     * @param declarationSnippet  the declaration header of this element (annotations, modifiers,
     *                            name, parameters, throws-clause, extends/implements) without the
     *                            body.  For CLASS/INTERFACE/METHOD/CONSTRUCTOR nodes this is the
     *                            opening line(s) up to and including the first {@code {}.
     *                            For FIELD, VARIABLE, TYPE_PARAM, and ANNOTATION_ATTRIBUTE nodes
     *                            (which have no body) this equals the full source snippet.
     *                            For LAMBDA nodes the field is an empty string.
     */
    public record GraphNode(
            String id,
            NodeKind kind,
            String simpleName,
            String filePath,
            int startLine,
            int endLine,
            String sourceSnippet,
            String declarationSnippet
    ) {}

    /** A directed, typed edge between two graph nodes. */
    public record GraphEdge(
            String caller,
            EdgeKind kind,
            String callee,
            String filePath,
            int line
    ) {}

    /** The full symbol graph of a project. */
    public static class ProjectGraph {
        public final Map<String, GraphNode>       nodes        = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesFrom    = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesTo      = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> bySimpleName = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byLine       = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byFile       = new LinkedHashMap<>();

        /**
         * All file paths stored in the graph (relative to the project root that
         * was passed to {@link AstGraphService#buildGraph}).
         * Used by {@link AstGraphService#normalizeFilePath} for suffix matching.
         */
        public Set<String> allFilePaths() {
            return byFile.keySet();
        }

        void addNode(GraphNode n) {
            nodes.put(n.id(), n);
            bySimpleName.computeIfAbsent(n.simpleName(), k -> new ArrayList<>()).add(n);
            byLine.computeIfAbsent(n.filePath() + "#" + n.startLine(), k -> new ArrayList<>()).add(n);
            byFile.computeIfAbsent(n.filePath(), k -> new ArrayList<>()).add(n);
        }

        void addEdge(GraphEdge e) {
            edgesFrom.computeIfAbsent(e.caller(), k -> new ArrayList<>()).add(e);
            edgesTo.computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(e);
        }

        public List<GraphEdge> outgoing(String nodeId) {
            return edgesFrom.getOrDefault(nodeId, List.of());
        }

        public List<GraphEdge> incoming(String nodeId) {
            return edgesTo.getOrDefault(nodeId, List.of());
        }

        public List<GraphEdge> outgoing(String nodeId, EdgeKind kind) {
            return outgoing(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }

        public List<GraphEdge> incoming(String nodeId, EdgeKind kind) {
            return incoming(nodeId).stream().filter(e -> e.kind() == kind).toList();
        }

        /** All nodes whose line-range covers the given line in a file. */
        public List<GraphNode> nodesAtLine(String relPath, int line) {
            return byFile.getOrDefault(relPath, List.of()).stream()
                    .filter(n -> n.startLine() <= line && n.endLine() >= line)
                    .toList();
        }

        /**
         * Rebuilds indexes from serialized node/edge lists (see {@link ProjectGraphSerialization}).
         */
        public static ProjectGraph reconstruct(List<GraphNode> nodes, List<GraphEdge> edges) {
            ProjectGraph g = new ProjectGraph();
            for (GraphNode n : nodes) {
                g.addNode(n);
            }
            for (GraphEdge e : edges) {
                g.addEdge(e);
            }
            return g;
        }

        /** Merges all nodes and edges from {@code other} into this graph (no deduplication). */
        public void mergeFrom(ProjectGraph other) {
            for (GraphNode n : other.nodes.values()) {
                addNode(n);
            }
            for (List<GraphEdge> list : other.edgesFrom.values()) {
                for (GraphEdge e : list) {
                    addEdge(e);
                }
            }
        }

        public static ProjectGraph merge(Iterable<ProjectGraph> parts) {
            ProjectGraph g = new ProjectGraph();
            for (ProjectGraph p : parts) {
                g.mergeFrom(p);
            }
            return g;
        }
    }

    // ------------------------------------------------------------------
    // Dependencies & cache
    // ------------------------------------------------------------------

    private final EdgeKindConfig edgeConfig;
    private final GraphCacheProperties graphCacheProperties;
    private final ProjectGraphCacheStore graphCacheStore;

    /** Merged graph per project version (invalidated when any segment is evicted). */
    private final Map<ProjectKey, ProjectGraph> mergedCache = new ConcurrentHashMap<>();
    /** Optional per-segment graphs for selective load / memory eviction. */
    private final Map<ProjectSegmentKey, ProjectGraph> segmentMemoryCache = new ConcurrentHashMap<>();

    /**
     * Versioned key for {@link #buildGraph(ProjectKey)} and disk cache file names.
     */
    public ProjectKey projectKey(Path projectRoot) {
        Path n = projectRoot.toAbsolutePath().normalize();
        return new ProjectKey(n, ProjectFingerprint.compute(n));
    }

    public ProjectGraph buildGraph(Path projectRoot) {
        return buildGraph(projectKey(projectRoot));
    }

    /**
     * Builds or returns a merged cached graph. Tries sharded disk cache (or legacy single JSON), then Spoon.
     * Shards are also stored in {@link #segmentMemoryCache} for selective use.
     */
    public ProjectGraph buildGraph(ProjectKey key) {
        return mergedCache.computeIfAbsent(key, this::loadOrBuildMerged);
    }

    private ProjectGraph loadOrBuildMerged(ProjectKey key) {
        Optional<Map<String, ProjectGraph>> fromDisk = graphCacheStore.tryLoadAllSegments(key);
        if (fromDisk.isPresent()) {
            putSegmentsInMemory(key, fromDisk.get());
            return ProjectGraph.merge(fromDisk.get().values());
        }
        BuildGraphOutcome built = doBuildGraphOutcome(key.projectRoot());
        Map<String, ProjectGraph> parts = ProjectGraphPartitioner.partition(
                built.graph(), key.projectRoot(), built.sourcesJarPaths());
        if (!parts.isEmpty()) {
            try {
                graphCacheStore.savePartitioned(key, parts);
            } catch (IOException e) {
                log.warn("Failed to persist graph cache for {}: {}", key.projectRoot(), e.getMessage());
            }
            putSegmentsInMemory(key, parts);
        }
        return built.graph();
    }

    private void putSegmentsInMemory(ProjectKey key, Map<String, ProjectGraph> parts) {
        for (Map.Entry<String, ProjectGraph> e : parts.entrySet()) {
            segmentMemoryCache.put(new ProjectSegmentKey(key, e.getKey()), e.getValue());
        }
    }

    /**
     * Loads one segment from memory or disk (does not populate merged cache).
     */
    public Optional<ProjectGraph> getSegment(ProjectKey key, String segmentId) {
        ProjectSegmentKey sk = new ProjectSegmentKey(key, segmentId);
        ProjectGraph g = segmentMemoryCache.get(sk);
        if (g != null) {
            return Optional.of(g);
        }
        Optional<ProjectGraph> fromDisk = graphCacheStore.tryLoadSegment(key, segmentId);
        fromDisk.ifPresent(gg -> segmentMemoryCache.put(sk, gg));
        return fromDisk;
    }

    /** Removes a segment from memory; next {@link #buildGraph(ProjectKey)} will rebuild merge from remaining segments or disk. */
    public void evictSegmentFromMemory(ProjectSegmentKey segmentKey) {
        segmentMemoryCache.remove(segmentKey);
        mergedCache.remove(segmentKey.projectKey());
    }

    /**
     * Deserializes all segments from disk and merges — does not update in-memory caches.
     */
    public Optional<ProjectGraph> loadSerializedGraph(ProjectKey key) {
        return graphCacheStore.tryLoadAllSegments(key).map(m -> ProjectGraph.merge(m.values()));
    }

    public void invalidate(Path projectRoot) {
        Path n = projectRoot.toAbsolutePath().normalize();
        mergedCache.keySet().removeIf(k -> k.projectRoot().equals(n));
        segmentMemoryCache.keySet().removeIf(k -> k.projectKey().projectRoot().equals(n));
        graphCacheStore.deleteAllForRoot(n);
    }

    /** Drops memory and disk cache for exactly this root + fingerprint (other versions of the same repo stay cached). */
    public void invalidate(ProjectKey key) {
        mergedCache.remove(key);
        segmentMemoryCache.keySet().removeIf(k -> k.projectKey().equals(key));
        graphCacheStore.delete(key);
    }

    // ------------------------------------------------------------------
    // Path normalisation
    // ------------------------------------------------------------------

    /**
     * Translates a path coming from a GitLab diff (e.g.
     * {@code gl-hooks/src/main/java/com/example/Foo.java}) into the
     * relative path stored inside the graph (e.g.
     * {@code src/main/java/com/example/Foo.java}).
     *
     * <p>Strategy: walk through all file paths that the graph actually
     * contains and return the first one that the incoming path ends with
     * (or is equal to). This handles both mono-repo sub-module prefixes
     * and cases where the paths are already identical.
     *
     * @param diffPath  path as reported by GitLab diff
     * @param graph     the project graph to look up known paths in
     * @return the matching graph-relative path, or {@code diffPath} unchanged
     *         when no suffix match is found
     */
    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String normalised = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (normalised.equals(knownNorm)) return known;
            if (normalised.endsWith("/" + knownNorm)) return known;
            if (knownNorm.endsWith("/" + normalised)) return known;
        }
        String[] parts = normalised.split("/");
        for (int i = 1; i < parts.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            if (graph.byFile.containsKey(candidate)) return candidate;
        }
        log.debug("normalizeFilePath: no match in graph for '{}'", diffPath);
        return diffPath;
    }

    // ------------------------------------------------------------------
    // Graph construction
    // ------------------------------------------------------------------

    /** Directory segments that should never be fed to Spoon as source roots. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    /**
     * Collects only real source directories (src/main/java, src/test/java,
     * or the project root itself), explicitly skipping build/target output
     * directories that contain duplicate .java files and trigger
     * "type already defined" errors in JDT.
     */
    private List<String> collectSourceRoots(Path projectRoot) throws IOException {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        );
        List<String> roots = candidates.stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
        if (!roots.isEmpty()) {
            log.debug("Using standard source roots: {}", roots);
            return roots;
        }

        List<String> fallback = new ArrayList<>();
        try (Stream<Path> top = Files.list(projectRoot)) {
            top.filter(Files::isDirectory)
               .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
               .filter(p -> !p.getFileName().toString().startsWith("."))
               .forEach(p -> fallback.add(p.toString()));
        }
        if (!fallback.isEmpty()) {
            log.debug("Fallback source roots: {}", fallback);
            return fallback;
        }

        return List.of(projectRoot.toString());
    }

    private Path resolveSourcesRemoteCacheDir(Path projectRoot) {
        String d = graphCacheProperties.getSourcesRemoteCacheDir();
        if (d != null && !d.isBlank()) {
            return Path.of(d);
        }
        return projectRoot.toAbsolutePath().normalize().resolve(".mrrag-sources-cache");
    }

    private record BuildGraphOutcome(ProjectGraph graph, List<String> sourcesJarPaths) {}

    @SuppressWarnings("unchecked")
    private BuildGraphOutcome doBuildGraphOutcome(Path projectRoot) {
        log.info("Building Spoon AST graph for {}", projectRoot);
        List<String> sourcesJarPaths = new ArrayList<>();
        try {
            List<String> sourceRoots = collectSourceRoots(projectRoot);
            log.info("Source roots for Spoon: {}", sourceRoots);

            Launcher launcher = new Launcher();
            sourceRoots.forEach(launcher::addInputResource);

            ClasspathResolver.tryResolve(projectRoot).ifPresentOrElse(
                    r -> {
                        launcher.getEnvironment().setNoClasspath(false);
                        launcher.getEnvironment().setSourceClasspath(r.entries());
                        log.info("Spoon using {} compileClasspath ({} entries, {} remote repo URLs) for {}",
                                r.source(), r.entries().length, r.remoteRepositories().size(), projectRoot);
                        if (graphCacheProperties.isSourcesJarsEnabled()) {
                            Path sourcesCacheDir = resolveSourcesRemoteCacheDir(projectRoot);
                            List<String> sj = SourcesJarClasspathAugmentor.collectSourcesJars(
                                    r,
                                    sourcesCacheDir,
                                    graphCacheProperties.isSourcesRemoteEnabled());
                            sourcesJarPaths.addAll(sj);
                            for (String jar : sj) {
                                launcher.addInputResource(jar);
                            }
                            if (!sj.isEmpty()) {
                                log.info("Spoon input includes {} *-sources.jar from dependency classpath",
                                        sj.size());
                            }
                        }
                    },
                    () -> {
                        launcher.getEnvironment().setNoClasspath(true);
                        log.debug("Spoon noClasspath mode for {} (Gradle/Maven classpath not available)",
                                projectRoot);
                    });

            launcher.getEnvironment().setCommentEnabled(false);
            launcher.getEnvironment().setAutoImports(false);
            try {
                launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
            } catch (NoSuchMethodError ignored) {}

            CtModel model;
            try {
                model = launcher.buildModel();
            } catch (ModelBuildingException mbe) {
                log.warn("Spoon ModelBuildingException for {} — using partial model. Cause: {}",
                        projectRoot, mbe.getMessage());
                model = launcher.getModel();
                if (model == null) {
                    log.error("Spoon returned null model for {}, returning empty graph", projectRoot);
                    return new BuildGraphOutcome(new ProjectGraph(), List.copyOf(sourcesJarPaths));
                }
            } catch (AssertionError ae) {
                // Spoon 11.x / JDT internal assertion failure (e.g. ReferenceBuilder.setPackageOrDeclaringType)
                // triggered when processing *-sources.jar with unresolved types inside lambdas.
                // Fall back to whatever partial model JDT managed to build before the failure.
                log.warn("Spoon AssertionError for {} — using partial model (JDT internal).",
                        projectRoot, ae);
                model = launcher.getModel();
                if (model == null) {
                    log.error("Spoon returned null model after AssertionError for {}, returning empty graph",
                            projectRoot);
                    return new BuildGraphOutcome(new ProjectGraph(), List.copyOf(sourcesJarPaths));
                }
            }

            // Read source files into memory once for verbatim snippet extraction.
            // Key: relative path (same as stored in GraphNode.filePath).
            Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
            String root = projectRoot.toString();
            for (String srcRoot : sourceRoots) {
                Path srcRootPath = Path.of(srcRoot);
                if (!Files.isDirectory(srcRootPath)) continue;
                try (Stream<Path> walk = Files.walk(srcRootPath)) {
                    walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            String rel = relPath(root, p.toAbsolutePath().toString());
                            try {
                                String[] lines = Files.readString(p, StandardCharsets.UTF_8).split("\n", -1);
                                sourceLines.put(rel, lines);
                            } catch (IOException e) {
                                log.warn("Cannot read source file {}: {}", p, e.getMessage());
                            }
                        });
                }
            }

            var graph = new ProjectGraph();

            // ── 1. Type nodes ────────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                String id = qualifiedName(type);
                if (id == null) return;
                String file = relPath(root, sourceFile(type));
                int[] ln = lines(type);

                NodeKind kind;
                if (type instanceof CtAnnotationType) {
                    kind = NodeKind.ANNOTATION;
                } else if (type instanceof CtInterface) {
                    kind = NodeKind.INTERFACE;
                } else {
                    kind = NodeKind.CLASS;
                }

                graph.addNode(new GraphNode(id, kind, type.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], type),
                        extractDeclaration(sourceLines, file, ln[0], ln[1], type)));

                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    type.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
                }
            });

            // ── 2. EXTENDS / IMPLEMENTS ─────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.EXTENDS) || edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                model.getElements(new TypeFilter<>(CtType.class)).forEach(type -> {
                    String id = qualifiedName(type);
                    if (id == null) return;
                    String file = relPath(root, sourceFile(type));
                    int[] ln = lines(type);

                    if (edgeConfig.isEnabled(EdgeKind.EXTENDS)) {
                        if (type instanceof CtClass<?> cls) {
                            var superRef = cls.getSuperclass();
                            if (superRef != null)
                                graph.addEdge(new GraphEdge(
                                        id, EdgeKind.EXTENDS, superRef.getQualifiedName(), file, ln[0]
                                ));
                        } else if (type instanceof CtInterface<?> iface) {
                            iface.getSuperInterfaces().forEach(superRef ->
                                    graph.addEdge(new GraphEdge(
                                            id, EdgeKind.EXTENDS, superRef.getQualifiedName(), file, ln[0]
                                    ))
                            );
                        }
                    }
                    if (edgeConfig.isEnabled(EdgeKind.IMPLEMENTS)) {
                        if (type instanceof CtClass<?> cls) {
                            cls.getSuperInterfaces().forEach(ifRef ->
                                    graph.addEdge(new GraphEdge(
                                            id, EdgeKind.IMPLEMENTS, ifRef.getQualifiedName(),
                                            file, ln[0]
                                    ))
                            );
                        }
                    }
                });
            }

            // ── 3. TYPE_PARAM nodes ──────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.HAS_TYPE_PARAM)) {
                model.getElements(new TypeFilter<>(CtFormalTypeDeclarer.class)).forEach(declarer -> {
                    String ownerId = formalDeclarerId(declarer);
                    if (ownerId == null) return;
                    String file = relPath(root, sourceFile((CtElement) declarer));
                    int[] ownerLn = lines((CtElement) declarer);

                    declarer.getFormalCtTypeParameters().forEach(tp -> {
                        String tpId = typeParamId(ownerId, tp);
                        int[] tpLn = lines(tp);
                        graph.addNode(new GraphNode(tpId, NodeKind.TYPE_PARAM, tp.getSimpleName(),
                                file, tpLn[0], tpLn[1],
                                extractSource(sourceLines, file, tpLn[0], tpLn[1], tp),
                                extractDeclaration(sourceLines, file, tpLn[0], tpLn[1], tp)));
                        graph.addEdge(new GraphEdge(
                                ownerId, EdgeKind.HAS_TYPE_PARAM, tpId,
                                file, ownerLn[0]
                        ));

                        if (edgeConfig.isEnabled(EdgeKind.HAS_BOUND)) {
                            CtTypeReference<?> superCls = tp.getSuperclass();
                            if (superCls != null && !superCls.getQualifiedName().equals("java.lang.Object")) {
                                graph.addEdge(new GraphEdge(
                                        tpId, EdgeKind.HAS_BOUND,
                                        superCls.getQualifiedName(), file, tpLn[0]));
                            }
                            tp.getSuperInterfaces().forEach(bound ->
                                    graph.addEdge(new GraphEdge(
                                            tpId, EdgeKind.HAS_BOUND,
                                            bound.getQualifiedName(), file, tpLn[0])));
                        }
                    });
                });
            }

            // ── 4. Method nodes ───────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtMethod.class)).forEach(m -> {
                String id = typeMemberExecId(m);
                if (id == null) return;
                String file = relPath(root, sourceFile(m));
                int[] ln = lines(m);
                graph.addNode(new GraphNode(id, NodeKind.METHOD, m.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], m),
                        extractDeclaration(sourceLines, file, ln[0], ln[1], m)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(m.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
                }
                //noinspection unchecked
                m.getTopDefinitions().stream().findFirst()
                        .filter(top -> top != m)
                        .ifPresent(superId ->
                                graph.addEdge(new GraphEdge(id, EdgeKind.OVERRIDES,
                                        typeMemberExecId((CtTypeMember) superId), file, ln[0])));
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    m.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
                }
            });

            // ── 5. Constructor nodes ────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtConstructor.class)).forEach(c -> {
                String id = typeMemberExecId(c);
                if (id == null) return;
                String file = relPath(root, sourceFile(c));
                int[] ln = lines(c);
                graph.addNode(new GraphNode(id, NodeKind.CONSTRUCTOR, c.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], c),
                        extractDeclaration(sourceLines, file, ln[0], ln[1], c)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(c.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
                }
            });

            // ── 6. Field nodes ────────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtField.class)).forEach(field -> {
                if (field.getDeclaringType() instanceof CtAnnotationType) return;
                String id = fieldId(field);
                if (id == null) return;
                String file = relPath(root, sourceFile(field));
                int[] ln = lines(field);
                graph.addNode(new GraphNode(id, NodeKind.FIELD, field.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], field),
                        extractDeclaration(sourceLines, file, ln[0], ln[1], field)));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    String classId = qualifiedName(field.getDeclaringType());
                    if (classId != null)
                        graph.addEdge(new GraphEdge(classId, EdgeKind.DECLARES, id, file, ln[0]));
                }
                if (edgeConfig.isEnabled(EdgeKind.ANNOTATED_WITH)) {
                    field.getAnnotations().forEach(ann ->
                            graph.addEdge(new GraphEdge(
                                    id, EdgeKind.ANNOTATED_WITH, ann.getAnnotationType().getQualifiedName(),
                                    file, ln[0]
                            ))
                    );
                }
            });

            // ── 7. Local variable + parameter nodes ───────────────────────────────────
            model.getElements(new TypeFilter<>(CtVariable.class)).forEach(v -> {
                if (v instanceof CtField) return;
                String id = varId(v);
                if (id == null) return;
                String file = relPath(root, sourceFile(v));
                int[] ln = lines(v);
                graph.addNode(new GraphNode(id, NodeKind.VARIABLE, v.getSimpleName(),
                        file, ln[0], ln[1],
                        extractSource(sourceLines, file, ln[0], ln[1], v),
                        extractDeclaration(sourceLines, file, ln[0], ln[1], v)));
            });

            // ── 8. ANNOTATION_ATTRIBUTE nodes ──────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.ANNOTATION_ATTR)) {
                model.getElements(new TypeFilter<>(CtAnnotationType.class)).forEach(ann -> {
                    String annoId = qualifiedName(ann);
                    if (annoId == null) return;
                    String file = relPath(root, sourceFile(ann));

                    @SuppressWarnings("unchecked")
                    Collection<CtMethod<?>> methods =
                            (Collection<CtMethod<?>>) (Collection<?>) ann.getMethods();
                    methods.forEach(m -> {
                        String attrId = annoId + "#" + m.getSimpleName();
                        int[] ln = lines(m);
                        graph.addNode(new GraphNode(attrId, NodeKind.ANNOTATION_ATTRIBUTE,
                                m.getSimpleName(), file, ln[0], ln[1],
                                extractSource(sourceLines, file, ln[0], ln[1], m),
                                extractDeclaration(sourceLines, file, ln[0], ln[1], m)));
                        graph.addEdge(new GraphEdge(
                                annoId, EdgeKind.ANNOTATION_ATTR, attrId, file, ln[0]
                        ));
                    });
                });
            }

            // ── 9. Lambda nodes ────────────────────────────────────────────────────────
            model.getElements(new TypeFilter<>(CtLambda.class)).forEach(lambda -> {
                String file = relPath(root, sourceFile(lambda));
                int[] ln = lines(lambda);
                String id = "lambda@" + file + ":" + ln[0];
                // Verbatim file lines often span the whole statement (e.g. while (...));
                // Spoon pretty-print keeps only the lambda expression for context.
                String lambdaSnippet = snippet(lambda);
                graph.addNode(new GraphNode(id, NodeKind.LAMBDA, "\u03bb",
                        file, ln[0], ln[1],
                        lambdaSnippet,
                        ""));

                if (edgeConfig.isEnabled(EdgeKind.DECLARES)) {
                    CtMethod<?> em = lambda.getParent(CtMethod.class);
                    if (em != null) {
                        String encId = typeMemberExecId(em);
                        if (encId != null)
                            graph.addEdge(new GraphEdge(encId, EdgeKind.DECLARES, id, file, ln[0]));
                    } else {
                        CtConstructor<?> ec = lambda.getParent(CtConstructor.class);
                        if (ec != null) {
                            String encId = typeMemberExecId(ec);
                            if (encId != null)
                                graph.addEdge(new GraphEdge(encId, EdgeKind.DECLARES, id, file, ln[0]));
                        }
                    }
                }
            });

            // ── 10. INVOKES ───────────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                model.getElements(new TypeFilter<>(CtInvocation.class)).forEach(inv -> {
                    String callerId = nearestExecId(inv);
                    if (callerId == null) return;
                    graph.addEdge(new GraphEdge(
                            callerId, EdgeKind.INVOKES, execRefIdForChainedInvocation(inv),
                            relPath(root, sourceFile(inv)), posLine(inv)
                    ));
                });
            }

            // ── 11. INSTANTIATES / INSTANTIATES_ANONYMOUS ───────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.INSTANTIATES) || edgeConfig.isEnabled(EdgeKind.INSTANTIATES_ANONYMOUS)) {
                model.getElements(new TypeFilter<>(CtConstructorCall.class)).forEach(cc -> {
                    String callerId = nearestExecId(cc);
                    if (callerId == null) return;
                    String typeId = cc.getExecutable().getDeclaringType() != null
                            ? cc.getExecutable().getDeclaringType().getQualifiedName() : "?";
                    boolean isAnon = cc instanceof CtNewClass;
                    EdgeKind kind = isAnon ? EdgeKind.INSTANTIATES_ANONYMOUS : EdgeKind.INSTANTIATES;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                callerId, kind, typeId,
                                relPath(root, sourceFile(cc)), posLine(cc)
                        ));
                    if (edgeConfig.isEnabled(EdgeKind.INVOKES)) {
                        graph.addEdge(new GraphEdge(
                                callerId, EdgeKind.INVOKES, execRefId(cc.getExecutable(), cc),
                                relPath(root, sourceFile(cc)), posLine(cc)
                        ));
                    }
                });
            }

            // ── 12. REFERENCES_METHOD ─────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_METHOD)) {
                model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class)).forEach(ref -> {
                    String callerId = nearestExecId(ref);
                    if (callerId == null) return;
                    graph.addEdge(new GraphEdge(
                            callerId, EdgeKind.REFERENCES_METHOD, execRefId(ref.getExecutable(), ref),
                            relPath(root, sourceFile(ref)), posLine(ref)
                    ));
                });
            }

            // ── 13. READS_FIELD / WRITES_FIELD ────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.READS_FIELD) || edgeConfig.isEnabled(EdgeKind.WRITES_FIELD)) {
                model.getElements(new TypeFilter<>(CtFieldAccess.class)).forEach(fa -> {
                    String execId = nearestExecId(fa);
                    if (execId == null) return;
                    CtFieldReference<?> ref = fa.getVariable();
                    String fId = ref.getDeclaringType() != null
                            ? ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName()
                            : "?." + ref.getSimpleName();
                    EdgeKind kind = (fa instanceof CtFieldWrite) ? EdgeKind.WRITES_FIELD : EdgeKind.READS_FIELD;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                execId, kind, fId,
                                relPath(root, sourceFile(fa)), posLine(fa)
                        ));
                });
            }

            // ── 14. READS_LOCAL_VAR / WRITES_LOCAL_VAR ─────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.READS_LOCAL_VAR) || edgeConfig.isEnabled(EdgeKind.WRITES_LOCAL_VAR)) {
                model.getElements(new TypeFilter<>(CtVariableAccess.class)).forEach(va -> {
                    if (va instanceof CtFieldAccess) return;
                    String execId = nearestExecId(va);
                    if (execId == null) return;
                    String vId = varRefId(va.getVariable());
                    EdgeKind kind = (va instanceof CtVariableWrite) ? EdgeKind.WRITES_LOCAL_VAR : EdgeKind.READS_LOCAL_VAR;
                    if (edgeConfig.isEnabled(kind))
                        graph.addEdge(new GraphEdge(
                                execId, kind, vId,
                                relPath(root, sourceFile(va)), posLine(va)
                        ));
                });
            }

            // ── 15. THROWS ────────────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.THROWS)) {
                model.getElements(new TypeFilter<>(CtThrow.class)).forEach(thr -> {
                    String execId = nearestExecId(thr);
                    if (execId == null) return;
                    CtExpression<?> thrown = thr.getThrownExpression();
                    String typeId = "?";
                    if (thrown instanceof CtConstructorCall<?> cc
                            && cc.getExecutable().getDeclaringType() != null) {
                        typeId = cc.getExecutable().getDeclaringType().getQualifiedName();
                    }
                    graph.addEdge(new GraphEdge(
                            execId, EdgeKind.THROWS, typeId,
                            relPath(root, sourceFile(thr)), posLine(thr)
                    ));
                });
            }

            // ── 16. REFERENCES_TYPE ───────────────────────────────────────────────────────
            if (edgeConfig.isEnabled(EdgeKind.REFERENCES_TYPE)) {
                model.getElements(new TypeFilter<>(CtTypeAccess.class)).forEach(ta -> {
                    String execId = nearestExecId(ta);
                    if (execId == null) return;
                    if (ta.getAccessedType() == null) return;
                    graph.addEdge(new GraphEdge(
                            execId, EdgeKind.REFERENCES_TYPE, ta.getAccessedType().getQualifiedName(),
                            relPath(root, sourceFile(ta)), posLine(ta)
                    ));
                });
            }

            log.info("Spoon graph built: {} nodes, {} edge-sources",
                    graph.nodes.size(), graph.edgesFrom.size());
            return new BuildGraphOutcome(graph, List.copyOf(sourcesJarPaths));

        } catch (IOException e) {
            throw new RuntimeException("Failed to collect source roots for " + projectRoot, e);
        }
    }

    // ------------------------------------------------------------------
    // Source snippet helpers
    // ------------------------------------------------------------------

    /**
     * Returns verbatim original source lines for {@code startLine..endLine} when
     * the file is present in {@code sourceLines}; falls back to Spoon
     * {@code toString()} otherwise (external/synthetic nodes).
     *
     * <p>When the fallback is used, {@code startLine} should already be {@code -1}
     * (set by {@link #lines(CtElement)} for elements without a valid position),
     * so callers can distinguish external snippets from project snippets.
     *
     * @param sourceLines map of relative file path → source lines (1-based index = array[line-1])
     * @param filePath    relative path of the file
     * @param startLine   first line, 1-based; {@code -1} or {@code 0} triggers fallback
     * @param endLine     last line, 1-based
     * @param el          Spoon element used as fallback
     * @return non-null source text
     */
    private static String extractSource(Map<String, String[]> sourceLines,
                                        String filePath, int startLine, int endLine,
                                        CtElement el) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = sourceLines.get(filePath);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to   = Math.min(lines.length, endLine);
                if (from < to) {
                    return String.join("\n", Arrays.copyOfRange(lines, from, to));
                }
            }
        }
        // fallback: Spoon pretty-print (external dependency or missing file)
        return snippet(el);
    }

    /**
     * Extracts the <em>declaration header</em> of a Java element from verbatim
     * source lines — i.e. the part before the opening {@code {}.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Start from {@code startLine} and collect lines until the first line
     *       that contains an unquoted {@code {}, stopping right after it (inclusive).</li>
     *   <li>If no {@code {} is found within the element's line range (e.g. an
     *       abstract method, an interface method, a field, a local variable, a
     *       parameter, a type parameter, or an annotation attribute), return the
     *       full {@code startLine..endLine} snippet — that <em>is</em> the
     *       declaration.</li>
     *   <li>If source lines are unavailable (external/synthetic node), fall back to
     *       Spoon's pretty-printed first line.</li>
     * </ol>
     *
     * @param sourceLines map of relative file path → source lines (1-based index = array[line-1])
     * @param filePath    relative path of the file
     * @param startLine   first source line of the element, 1-based
     * @param endLine     last source line of the element, 1-based
     * @param el          Spoon element used as fallback when source is unavailable
     * @return declaration header text; never {@code null}, may be empty
     */
    private static String extractDeclaration(Map<String, String[]> sourceLines,
                                             String filePath, int startLine, int endLine,
                                             CtElement el) {
        if (startLine <= 0 || endLine < startLine) {
            // External / synthetic: use first line of Spoon pretty-print as declaration
            String s = snippet(el);
            if (s.isBlank()) return "";
            int nl = s.indexOf('\n');
            return nl >= 0 ? s.substring(0, nl) : s;
        }

        String[] lines = sourceLines.get(filePath);
        if (lines == null) {
            String s = snippet(el);
            if (s.isBlank()) return "";
            int nl = s.indexOf('\n');
            return nl >= 0 ? s.substring(0, nl) : s;
        }

        int from = Math.max(0, startLine - 1);
        int to   = Math.min(lines.length, endLine);

        // Collect lines up to and including the first opening brace.
        // We do a very lightweight scan: ignore braces inside string literals
        // and character literals so that e.g.
        //   public String foo() { return "{"; }
        // is handled correctly (the method-body brace on the same line terminates
        // the declaration scan on that same line, which is correct).
        List<String> declLines = new ArrayList<>();
        boolean found = false;
        for (int i = from; i < to && !found; i++) {
            String line = lines[i];
            declLines.add(line);
            if (containsOpenBrace(line)) {
                found = true;
            }
        }

        if (declLines.isEmpty()) return "";
        return String.join("\n", declLines);
    }

    /**
     * Returns {@code true} if {@code line} contains an unquoted {@code {}.
     * Handles single-quoted ({@code '{'}) and double-quoted ({@code "{"}) literals
     * by skipping their content.
     */
    private static boolean containsOpenBrace(String line) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && (inDouble || inSingle)) { i++; continue; } // skip escape
            if (c == '"'  && !inSingle) { inDouble = !inDouble; continue; }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '{' && !inDouble && !inSingle) return true;
        }
        return false;
    }

    private static String snippet(CtElement el) {
        try { String s = el.toString(); return s != null ? s : ""; }
        catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------
    // ID helpers
    // ------------------------------------------------------------------

    private String qualifiedName(CtType<?> type) {
        if (type == null) return null;
        String q = type.getQualifiedName();
        return (q == null || q.isBlank()) ? null : q;
    }

    /**
     * Canonical graph id for a constructor: {@code ownerQualified#<init>(params)}.
     * Spoon's {@link CtExecutable#getSignature()} may prefix parameters with a
     * fully-qualified type name instead of the simple class name; we only keep
     * the parameter list so ids stay stable and {@code isConstructorId} matches.
     */
    private static String constructorExecutableId(String ownerQualified, String signature) {
        if (signature == null || signature.isBlank()) {
            return ownerQualified + "#<init>()";
        }
        int open = signature.indexOf('(');
        if (open < 0) {
            return ownerQualified + "#<init>()";
        }
        return ownerQualified + "#<init>" + signature.substring(open);
    }

    private String typeMemberExecId(CtTypeMember member) {
        if (member == null) return null;
        try {
            CtType<?> declaring = member.getDeclaringType();
            String owner = declaring != null ? declaring.getQualifiedName() : "?";
            if (member instanceof CtConstructor<?>) {
                return constructorExecutableId(owner, ((CtExecutable<?>) member).getSignature());
            }
            if (member instanceof CtExecutable<?> exec) {
                return owner + "#" + exec.getSignature();
            }
            return owner + "#" + member.getSimpleName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Qualified declaring type for a method/constructor reference.
     *
     * <p>Spoon often leaves {@link CtExecutableReference#getDeclaringType()} null for
     * virtual calls (receiver type is only known from the expression). In that case
     * pass the use-site element ({@link CtInvocation}, {@link CtConstructorCall}, …)
     * so we can infer the owner from the receiver ({@link CtExpression#getType()}),
     * static targets ({@link CtTypeAccess}), or the constructed type.
     */
    private static String qualifiedExecutableOwner(CtExecutableReference<?> ref, CtElement useSite) {
        try {
            if (ref.getDeclaringType() != null) {
                String q = ref.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) {
                    return q;
                }
            }
        } catch (Exception ignored) { }
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl instanceof CtTypeMember tm && tm.getDeclaringType() != null) {
                String q = tm.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) {
                    return q;
                }
            }
        } catch (Exception ignored) { }

        if (useSite instanceof CtInvocation inv) {
            String inferred = inferOwnerFromInvocation(inv);
            if (inferred != null) {
                return inferred;
            }
        }
        if (useSite instanceof CtConstructorCall<?> cc) {
            String inferred = inferOwnerFromConstructorCall(cc);
            if (inferred != null) {
                return inferred;
            }
        }
        if (useSite instanceof CtExecutableReferenceExpression ere) {
            String inferred = inferOwnerFromExecutableReferenceExpression(ere);
            if (inferred != null) {
                return inferred;
            }
        }
        return "?";
    }

    private static boolean isUsableQualifiedName(String q) {
        return q != null && !q.isBlank() && !"?".equals(q);
    }

    /**
     * Receiver-based owner for {@code receiver.method(...)} and static {@code Type.method(...)}.
     */
    private static String inferOwnerFromInvocation(CtInvocation<?> inv) {
        CtExpression<?> target = inv.getTarget();
        if (target instanceof CtTypeAccess<?> ta) {
            try {
                if (ta.getAccessedType() != null) {
                    String q = ta.getAccessedType().getQualifiedName();
                    if (isUsableQualifiedName(q)) {
                        return q;
                    }
                }
            } catch (Exception ignored) { }
        }
        if (target != null) {
            try {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    if (isUsableQualifiedName(q)) {
                        return q;
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String inferOwnerFromConstructorCall(CtConstructorCall<?> cc) {
        try {
            if (cc.getType() != null) {
                String q = cc.getType().getQualifiedName();
                if (isUsableQualifiedName(q)) {
                    return q;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String inferOwnerFromExecutableReferenceExpression(
            CtExecutableReferenceExpression<?, ?> ere) {
        try {
            CtExpression<?> target = ere.getTarget();
            if (target instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
                String q = ta.getAccessedType().getQualifiedName();
                if (isUsableQualifiedName(q)) {
                    return q;
                }
            }
            if (target != null) {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    if (isUsableQualifiedName(q)) {
                        return q;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String execRefId(CtExecutableReference<?> ref, CtElement useSite) {
        if (ref == null) return "unresolved";
        try {
            String owner = qualifiedExecutableOwner(ref, useSite);
            String sig = ref.getSignature();
            if (ref.isConstructor()) {
                return constructorExecutableId(owner, sig);
            }
            return owner + "#" + sig;
        } catch (Exception e) {
            return "unresolved:" + ref.getSimpleName();
        }
    }

    /**
     * Like {@link #execRefId(CtExecutableReference, CtElement)} for {@link CtInvocation}, but when the
     * callee owner is unknown ({@code ?#method(sig)}) and the receiver is another invocation
     * ({@code a().b(...)}), prefixes with the resolved id of the inner call so chains read as
     * {@code Type#outer()#inner(sig)} (helps noClasspath / unresolved declaring types).
     */
    private String execRefIdForChainedInvocation(CtInvocation<?> inv) {
        String base = execRefId(inv.getExecutable(), inv);
        if (!base.startsWith("?#")) {
            return base;
        }
        String suffix = base.substring(2);
        CtExpression<?> target = inv.getTarget();
        if (target instanceof CtInvocation<?> inner) {
            String innerId = execRefIdForChainedInvocation(inner);
            if (!innerId.startsWith("?")) {
                return innerId + "#" + suffix;
            }
        }
        return base;
    }

    private String fieldId(CtField<?> field) {
        if (field.getDeclaringType() == null) return null;
        return field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName();
    }

    private String varId(CtVariable<?> v) {
        if (!v.getPosition().isValidPosition()) return null;
        String file = v.getPosition().getFile() != null
                ? v.getPosition().getFile().getName() : "?";
        return "var@" + file + ":" + v.getPosition().getLine() + ":" + v.getSimpleName();
    }

    private String varRefId(CtVariableReference<?> ref) {
        if (ref == null) return "var@?";
        try {
            CtVariable<?> decl = ref.getDeclaration();
            if (decl != null) return varId(decl);
        } catch (Exception ignored) {}
        return "var@" + ref.getSimpleName();
    }

    private String typeParamId(String ownerId, CtTypeParameter tp) {
        return ownerId + "#<" + tp.getSimpleName() + ">";
    }

    private String formalDeclarerId(CtFormalTypeDeclarer declarer) {
        if (declarer instanceof CtType<?> t) return qualifiedName(t);
        if (declarer instanceof CtTypeMember m) return typeMemberExecId(m);
        return null;
    }

    private String nearestExecId(CtElement el) {
        CtMethod<?> m = el.getParent(CtMethod.class);
        if (m != null) return typeMemberExecId(m);
        CtConstructor<?> c = el.getParent(CtConstructor.class);
        if (c != null) return typeMemberExecId(c);
        return null;
    }

    // ------------------------------------------------------------------
    // Position / path helpers
    // ------------------------------------------------------------------

    private String sourceFile(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition() && pos.getFile() != null)
                return pos.getFile().getAbsolutePath();
        } catch (Exception ignored) {}
        return "";
    }

    private String relPath(String root, String abs) {
        if (abs.isEmpty()) return "unknown";
        return abs.startsWith(root)
                ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    /**
     * Returns {@code [startLine, endLine]} for the element, both 1-based.
     * Returns {@code [-1, -1]} when position information is unavailable
     * (external or synthetic node — no source file in this project).
     */
    private int[] lines(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition())
                return new int[]{ pos.getLine(), pos.getEndLine() };
        } catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    private int posLine(CtElement el) {
        try {
            var pos = el.getPosition();
            return pos.isValidPosition() ? pos.getLine() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
