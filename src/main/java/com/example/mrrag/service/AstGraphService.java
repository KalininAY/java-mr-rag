package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import com.example.mrrag.service.graph.GraphBuildService;
import com.example.mrrag.service.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.service.source.ProjectSource;
import com.example.mrrag.service.source.ProjectSourceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.compiler.ModelBuildingException;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.compiler.VirtualFile;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Primary implementation of {@link GraphBuildService}.
 *
 * <p>Builds an in-memory AST graph for a Java project using the
 * <a href="https://spoon.gforge.inria.fr/">Spoon</a> library.  The graph
 * captures class hierarchy, method calls, field usages, and annotation
 * relationships so that downstream components (review enrichment, RAG
 * context retrieval, …) can reason about code structure.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link #buildGraph(ProjectKey)} — preferred for local clones; uses
 *       a fingerprint-based persistent cache ({@link ProjectGraphCacheStore}).</li>
 *   <li>{@link #buildGraph(ProjectSourceProvider)} — universal entry; provider
 *       supplies sources from any origin (local clone, GitLab API, …).
 *       Automatically cached by {@link ProjectSourceProvider#projectId()} when
 *       the provider returns a non-blank id.</li>
 *   <li>{@link #buildGraph(Path)} — deprecated legacy shortcut for local
 *       clones; backed by an in-memory {@code localCache}.</li>
 * </ul>
 */
@Slf4j
@Service
public class AstGraphService implements GraphBuildService {

    // ------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------

    private final EdgeKindConfig          edgeConfig;
    private final GraphCacheProperties    cacheProps;
    private final ProjectGraphCacheStore  cacheStore;

    // ------------------------------------------------------------------
    // Graph model
    // ------------------------------------------------------------------

    /**
     * Represents the full AST graph for a project.
     *
     * <p>{@code byFile} maps a source-file path (relative to project root) to
     * the list of top-level classes declared in that file.
     */
    public static class ProjectGraph {
        public final Map<String, List<GraphNode>> byFile = new LinkedHashMap<>();
        public final List<GraphEdge>              edges  = new ArrayList<>();

        public Set<String> allFilePaths() { return byFile.keySet(); }
    }

    public enum NodeKind  { CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, METHOD, FIELD, CONSTRUCTOR }
    public enum EdgeKind  { EXTENDS, IMPLEMENTS, CALLS, USES_FIELD, HAS_PARAM_TYPE,
                            HAS_RETURN_TYPE, ANNOTATED_BY, HAS_MEMBER }

    public static class GraphNode {
        public final String   id;
        public final NodeKind kind;
        public final String   name;
        public final String   qualifiedName;
        public final String   filePath;
        public final int      lineStart;
        public final int      lineEnd;
        public final List<GraphEdge> edges = new ArrayList<>();

        public GraphNode(String id, NodeKind kind, String name,
                         String qualifiedName, String filePath,
                         int lineStart, int lineEnd) {
            this.id            = id;
            this.kind          = kind;
            this.name          = name;
            this.qualifiedName = qualifiedName;
            this.filePath      = filePath;
            this.lineStart     = lineStart;
            this.lineEnd       = lineEnd;
        }

        @Override public String toString() {
            return kind + ":" + qualifiedName + "@" + filePath + ":" + lineStart;
        }
    }

    public static class GraphEdge {
        public final EdgeKind  kind;
        public final GraphNode from;
        public final GraphNode to;
        public GraphEdge(EdgeKind kind, GraphNode from, GraphNode to) {
            this.kind = kind; this.from = from; this.to = to;
        }
        @Override public String toString() {
            return from.id + " --[" + kind + "]--> " + to.id;
        }
    }

    public static class GraphBuildStats {
        public int fileCount;
        public int nodeCount;
        public int edgeCount;
    }

    // ------------------------------------------------------------------
    // Caches
    // ------------------------------------------------------------------

    /**
     * Cache keyed by the {@link Path} of a local clone.
     * Only populated by the deprecated {@link #buildGraph(Path)} path;
     * {@link #buildGraph(ProjectSourceProvider)} uses {@code projectIdCache}.
     */
    private final Map<Path, ProjectGraph>        localCache      = new ConcurrentHashMap<>();
    private final Map<ProjectKey, ProjectGraph>  keyCache        = new ConcurrentHashMap<>();
    /**
     * Cache keyed by {@link com.example.mrrag.service.source.ProjectSourceProvider#projectId()}.
     * Covers both local clones and GitLab API providers that return a non-blank projectId.
     */
    private final Map<String, ProjectGraph>       projectIdCache  = new ConcurrentHashMap<>();

    /**
     * Primary Spring constructor — injects cache support.
     * {@code @Autowired} is required because a second single-arg constructor
     * is present for tests; without it Spring cannot determine which one to use.
     */
    @Autowired
    public AstGraphService(EdgeKindConfig edgeConfig,
                           GraphCacheProperties cacheProps,
                           ProjectGraphCacheStore cacheStore) {
        this.edgeConfig  = edgeConfig;
        this.cacheProps  = cacheProps;
        this.cacheStore  = cacheStore;
    }

    /** Minimal constructor for callers that do not need disk-cache support. */
    public AstGraphService(EdgeKindConfig edgeConfig) {
        this(edgeConfig, null, null);
    }

    // ------------------------------------------------------------------
    // ProjectKey helpers
    // ------------------------------------------------------------------

    /**
     * Derives a stable {@link ProjectKey} for the given local project root
     * by computing a {@link ProjectFingerprint}.
     */
    public ProjectKey projectKey(Path projectRoot) {
        return new ProjectKey(projectRoot, ProjectFingerprint.compute(projectRoot));
    }

    // ------------------------------------------------------------------
    // GraphBuildService — ProjectKey-based entry point
    // ------------------------------------------------------------------

    /**
     * Build (or return cached) symbol graph using a {@link ProjectKey}.
     *
     * <p>The method first consults the in-memory {@code keyCache}; on a miss
     * it checks the persistent {@link ProjectGraphCacheStore} (if configured);
     * finally it falls back to a full Spoon analysis and stores the result.
     */
    @Override
    public ProjectGraph buildGraph(ProjectKey key) throws Exception {
        ProjectGraph cached = keyCache.get(key);
        if (cached != null) {
            log.debug("keyCache hit for {}", key.projectRoot());
            return cached;
        }

        if (cacheStore != null) {
            Optional<ProjectGraph> persisted = cacheStore.load(key);
            if (persisted.isPresent()) {
                log.info("Disk-cache hit for {} (fingerprint {})", key.projectRoot(), key.fingerprint());
                keyCache.put(key, persisted.get());
                return persisted.get();
            }
        }

        log.info("Building graph for {} (fingerprint {})", key.projectRoot(), key.fingerprint());
        ProjectGraph graph = buildGraph(new LocalCloneProjectSourceProvider(key.projectRoot()));
        keyCache.put(key, graph);
        if (cacheStore != null) {
            cacheStore.store(key, graph);
        }
        return graph;
    }

    // ------------------------------------------------------------------
    // GraphBuildService — provider-based entry point (primary)
    // ------------------------------------------------------------------

    /**
     * Build a symbol graph from any {@link ProjectSourceProvider}.
     *
     * <p>When the provider returns a non-blank {@link com.example.mrrag.service.source.ProjectSourceProvider#projectId()},
     * the result is automatically cached in {@code projectIdCache} and returned
     * on subsequent calls without re-analysing sources.  Pass an empty
     * {@code projectId} to disable caching for a specific provider.
     */
    @Override
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        String pid = provider.projectId();
        if (!pid.isBlank()) {
            ProjectGraph cached = projectIdCache.get(pid);
            if (cached != null) {
                log.debug("AST graph cache hit for projectId='{}'", pid);
                return cached;
            }
        }
        log.info("Building AST graph via provider: {} (projectId='{}')",
                provider.getClass().getSimpleName(), pid.isBlank() ? "<none>" : pid);
        List<ProjectSource> sources = provider.getSources();
        log.info("Provider supplied {} .java files", sources.size());
        Path classpathRoot = provider.localProjectRoot().orElse(null);
        ProjectGraph graph = doBuildGraphFromSources(sources, classpathRoot);
        if (!pid.isBlank()) {
            projectIdCache.put(pid, graph);
            log.debug("AST graph cached for projectId='{}'", pid);
        }
        return graph;
    }

    /**
     * Evict a graph cached by {@link com.example.mrrag.service.source.ProjectSourceProvider#projectId()}.
     *
     * @param projectId the value previously returned by {@code provider.projectId()}
     */
    public void invalidateByProjectId(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            projectIdCache.remove(projectId);
            log.info("AST graph cache evicted for projectId='{}'", projectId);
        }
    }

    // ------------------------------------------------------------------
    // GraphBuildService — legacy local-path shortcut
    // ------------------------------------------------------------------

    /**
     * Build (or return cached) symbol graph from a locally cloned directory.
     *
     * @deprecated Use {@link #buildGraph(ProjectSourceProvider)} with
     *     {@link LocalCloneProjectSourceProvider} instead.
     */
    @Override
    @Deprecated
    public ProjectGraph buildGraph(Path projectRoot) {
        return localCache.computeIfAbsent(projectRoot, root -> {
            try {
                return buildGraph(new LocalCloneProjectSourceProvider(root));
            } catch (Exception e) {
                throw new RuntimeException("Failed to build graph for " + root, e);
            }
        });
    }

    @Override
    public void invalidate(Path projectRoot) {
        localCache.remove(projectRoot);
    }

    // ------------------------------------------------------------------
    // GraphBuildService — path normalisation
    // ------------------------------------------------------------------

    @Override
    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String norm = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (norm.equals(knownNorm))             return known;
            if (norm.endsWith("/" + knownNorm))     return known;
            if (knownNorm.endsWith("/" + norm))     return known;
        }
        String[] parts = norm.split("/");
        for (int i = 1; i < parts.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            if (graph.byFile.containsKey(candidate)) return candidate;
        }
        log.debug("normalizeFilePath: no match in graph for '{}'", diffPath);
        return diffPath;
    }

    // ------------------------------------------------------------------
    // Backward-compat: VirtualSource-based entry point (kept for old callers)
    // ------------------------------------------------------------------

    /**
     * @deprecated Pass a {@link com.example.mrrag.service.source.GitLabProjectSourceProvider}
     *     to {@link #buildGraph(ProjectSourceProvider)} instead.
     */
    @Deprecated
    public ProjectGraph buildGraphFromVirtualSources(
            List<ProjectSource> sources) throws Exception {
        return doBuildGraphFromSources(sources, null);
    }

    // ------------------------------------------------------------------
    // Core graph construction
    // ------------------------------------------------------------------

    /**
     * Builds a Spoon {@link CtModel} from the provided sources, then
     * translates it into a {@link ProjectGraph}.
     *
     * <p>All {@link ProjectSource} entries are fed to Spoon as
     * {@link VirtualFile} objects, so no temporary directory is needed
     * regardless of whether the files came from a local clone or an API.
     *
     * @param classpathRoot when non-null (e.g. local clone), try Gradle/Maven compile classpath for Spoon
     */
    private ProjectGraph doBuildGraphFromSources(List<ProjectSource> sources,
                                                  Path classpathRoot) throws Exception {
        if (sources.isEmpty()) {
            log.warn("No Java sources supplied — returning empty graph");
            return new ProjectGraph();
        }

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);
        try {
            launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        } catch (NoSuchMethodError ignored) { /* older Spoon */ }

        if (classpathRoot != null) {
            trySetClasspath(launcher, classpathRoot);
        }

        for (ProjectSource src : sources) {
            launcher.addInputResource(new VirtualFile(src.content(), src.path()));
        }

        CtModel model;
        try {
            model = launcher.buildModel();
        } catch (ModelBuildingException mbe) {
            log.warn("Spoon ModelBuildingException — continuing with partial model: {}", mbe.getMessage());
            model = launcher.getModel();
            if (model == null) {
                log.error("Spoon returned null model — returning empty graph");
                return new ProjectGraph();
            }
        }

        return buildGraphFromModel(model, sources);
    }

    // ------------------------------------------------------------------
    // Classpath helpers
    // ------------------------------------------------------------------

    private void trySetClasspath(Launcher launcher, Path projectRoot) {
        // Try Gradle build/classes first, then Maven target/classes
        Path[] candidates = {
            projectRoot.resolve("build/classes/java/main"),
            projectRoot.resolve("target/classes")
        };
        for (Path cp : candidates) {
            if (Files.isDirectory(cp)) {
                launcher.getEnvironment().setSourceClasspath(new String[]{ cp.toAbsolutePath().toString() });
                log.debug("Spoon classpath set to {}", cp);
                return;
            }
        }
        log.debug("No compiled classes found under {} — using noClasspath mode", projectRoot);
    }

    // ------------------------------------------------------------------
    // Graph construction from CtModel
    // ------------------------------------------------------------------

    private ProjectGraph buildGraphFromModel(CtModel model, List<ProjectSource> sources) {
        ProjectGraph graph = new ProjectGraph();

        // Index all source paths for file-path normalisation during edge building
        Map<String, String> qualifiedToFile = new LinkedHashMap<>();

        // Pass 1: create nodes
        Map<String, GraphNode> nodeById = new LinkedHashMap<>();
        for (CtType<?> type : model.getAllTypes()) {
            String filePath = sourcePathOf(type, sources);
            if (filePath == null) continue;

            GraphNode node = toNode(type, filePath);
            nodeById.put(node.id, node);
            graph.byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(node);
            qualifiedToFile.put(type.getQualifiedName(), filePath);

            // Members
            for (CtTypeMember member : type.getTypeMembers()) {
                if (member instanceof CtMethod<?> m) {
                    GraphNode mn = methodNode(m, filePath, type);
                    nodeById.put(mn.id, mn);
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_MEMBER, node, mn);
                    node.edges.add(e);
                    graph.edges.add(e);
                } else if (member instanceof CtField<?> f) {
                    GraphNode fn = fieldNode(f, filePath, type);
                    nodeById.put(fn.id, fn);
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_MEMBER, node, fn);
                    node.edges.add(e);
                    graph.edges.add(e);
                } else if (member instanceof CtConstructor<?> c) {
                    GraphNode cn = constructorNode(c, filePath, type);
                    nodeById.put(cn.id, cn);
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_MEMBER, node, cn);
                    node.edges.add(e);
                    graph.edges.add(e);
                }
            }
        }

        // Pass 2: build edges between types
        for (CtType<?> type : model.getAllTypes()) {
            GraphNode typeNode = nodeById.get(typeId(type));
            if (typeNode == null) continue;

            // EXTENDS
            if (type.getSuperclass() != null) {
                String superId = type.getSuperclass().getQualifiedName();
                GraphNode superNode = nodeById.get(superId);
                if (superNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.EXTENDS, typeNode, superNode);
                    typeNode.edges.add(e);
                    graph.edges.add(e);
                }
            }

            // IMPLEMENTS
            for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                GraphNode ifaceNode = nodeById.get(iface.getQualifiedName());
                if (ifaceNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.IMPLEMENTS, typeNode, ifaceNode);
                    typeNode.edges.add(e);
                    graph.edges.add(e);
                }
            }

            // ANNOTATED_BY
            for (CtAnnotation<?> ann : type.getAnnotations()) {
                GraphNode annNode = nodeById.get(ann.getAnnotationType().getQualifiedName());
                if (annNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.ANNOTATED_BY, typeNode, annNode);
                    typeNode.edges.add(e);
                    graph.edges.add(e);
                }
            }

            // Method-level: CALLS, HAS_PARAM_TYPE, HAS_RETURN_TYPE, USES_FIELD
            if (edgeConfig == null || edgeConfig.isMethodEdgesEnabled()) {
                buildMethodEdges(type, typeNode, nodeById, graph);
            }
        }

        log.info("Graph built: {} files, {} nodes, {} edges",
                graph.byFile.size(), nodeById.size(), graph.edges.size());
        return graph;
    }

    private void buildMethodEdges(CtType<?> type, GraphNode typeNode,
                                  Map<String, GraphNode> nodeById,
                                  ProjectGraph graph) {
        for (CtMethod<?> method : type.getMethods()) {
            GraphNode methodNode = nodeById.get(methodId(method, type));
            if (methodNode == null) continue;

            // HAS_RETURN_TYPE
            if (method.getType() != null) {
                GraphNode retNode = nodeById.get(method.getType().getQualifiedName());
                if (retNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_RETURN_TYPE, methodNode, retNode);
                    methodNode.edges.add(e);
                    graph.edges.add(e);
                }
            }

            // HAS_PARAM_TYPE
            for (CtParameter<?> param : method.getParameters()) {
                GraphNode paramNode = nodeById.get(param.getType().getQualifiedName());
                if (paramNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_PARAM_TYPE, methodNode, paramNode);
                    methodNode.edges.add(e);
                    graph.edges.add(e);
                }
            }

            // CALLS
            Set<String> visited = new HashSet<>();
            for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                try {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    if (exec.getDeclaringType() == null) continue;
                    String targetId = exec.getDeclaringType().getQualifiedName()
                            + "#" + exec.getSimpleName();
                    if (visited.add(targetId)) {
                        GraphNode targetNode = nodeById.get(targetId);
                        if (targetNode != null) {
                            GraphEdge e = new GraphEdge(EdgeKind.CALLS, methodNode, targetNode);
                            methodNode.edges.add(e);
                            graph.edges.add(e);
                        }
                    }
                } catch (Exception ignored) { /* Spoon may throw on unresolved refs */ }
            }

            // USES_FIELD
            for (CtFieldRead<?> fr : method.getElements(new TypeFilter<>(CtFieldRead.class))) {
                try {
                    CtFieldReference<?> ref = fr.getVariable();
                    if (ref.getDeclaringType() == null) continue;
                    String fieldId = ref.getDeclaringType().getQualifiedName()
                            + "." + ref.getSimpleName();
                    GraphNode fieldNode = nodeById.get(fieldId);
                    if (fieldNode != null) {
                        GraphEdge e = new GraphEdge(EdgeKind.USES_FIELD, methodNode, fieldNode);
                        methodNode.edges.add(e);
                        graph.edges.add(e);
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Node factory helpers
    // ------------------------------------------------------------------

    private GraphNode toNode(CtType<?> type, String filePath) {
        NodeKind kind = switch (type) {
            case CtInterface<?> i -> NodeKind.INTERFACE;
            case CtEnum<?> e     -> NodeKind.ENUM;
            case CtAnnotationType<?> a -> NodeKind.ANNOTATION_TYPE;
            default              -> NodeKind.CLASS;
        };
        return new GraphNode(
                typeId(type), kind,
                type.getSimpleName(), type.getQualifiedName(),
                filePath, posLine(type), posEndLine(type));
    }

    private GraphNode methodNode(CtMethod<?> m, String filePath, CtType<?> owner) {
        String id = methodId(m, owner);
        return new GraphNode(id, NodeKind.METHOD,
                m.getSimpleName(), id, filePath, posLine(m), posEndLine(m));
    }

    private GraphNode fieldNode(CtField<?> f, String filePath, CtType<?> owner) {
        String id = owner.getQualifiedName() + "." + f.getSimpleName();
        return new GraphNode(id, NodeKind.FIELD,
                f.getSimpleName(), id, filePath, posLine(f), posEndLine(f));
    }

    private GraphNode constructorNode(CtConstructor<?> c, String filePath, CtType<?> owner) {
        String id = owner.getQualifiedName() + "#<init>";
        return new GraphNode(id, NodeKind.CONSTRUCTOR,
                "<init>", id, filePath, posLine(c), posEndLine(c));
    }

    private static String typeId(CtType<?> type) {
        return type.getQualifiedName();
    }

    private static String methodId(CtMethod<?> m, CtType<?> owner) {
        return owner.getQualifiedName() + "#" + m.getSimpleName();
    }

    // ------------------------------------------------------------------
    // Source-path resolution
    // ------------------------------------------------------------------

    private String sourcePathOf(CtType<?> type, List<ProjectSource> sources) {
        try {
            var pos = type.getPosition();
            if (pos != null && pos.isValidPosition() && pos.getFile() != null) {
                String fileName = pos.getFile().getName(); // simple name, e.g. Foo.java
                // Find matching source by suffix
                for (ProjectSource src : sources) {
                    String sp = src.path().replace('\\', '/');
                    if (sp.endsWith("/" + fileName) || sp.equals(fileName)) return src.path();
                }
            }
        } catch (Exception ignored) { }
        // Fallback: derive from qualified name
        String qn = type.getQualifiedName().replace('.', '/') + ".java";
        for (ProjectSource src : sources) {
            String sp = src.path().replace('\\', '/');
            if (sp.endsWith(qn)) return src.path();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Position helpers
    // ------------------------------------------------------------------

    private int posLine(CtElement el) {
        try { var p = el.getPosition(); return p.isValidPosition() ? p.getLine() : -1; }
        catch (Exception e) { return -1; }
    }

    private int posEndLine(CtElement el) {
        try { var p = el.getPosition(); return p.isValidPosition() ? p.getEndLine() : -1; }
        catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------
    // Graph statistics
    // ------------------------------------------------------------------

    public GraphBuildStats stats(ProjectGraph graph) {
        GraphBuildStats s = new GraphBuildStats();
        s.fileCount = graph.byFile.size();
        s.nodeCount = graph.byFile.values().stream().mapToInt(List::size).sum();
        s.edgeCount = graph.edges.size();
        return s;
    }

    // ------------------------------------------------------------------
    // Deprecated internal helper kept to avoid compilation errors in tests
    // ------------------------------------------------------------------

    /** @deprecated internal use only */
    @Deprecated
    int[] positionOf(CtElement el) {
        try {
            var p = el.getPosition();
            return p.isValidPosition() ? new int[]{ p.getLine(), p.getEndLine() } : new int[]{ -1, -1 };
        } catch (Exception e) {
            return new int[]{ -1, -1 };
        }
    }

    private int posLine(CtElement el) {
        try { var p = el.getPosition(); return p.isValidPosition() ? p.getLine() : -1; }
        catch (Exception e) { return -1; }
    }
}
