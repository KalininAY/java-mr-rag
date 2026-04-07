package com.example.mrrag.service;

import com.example.mrrag.config.EdgeKindConfig;
import com.example.mrrag.config.GraphCacheProperties;
import com.example.mrrag.model.graph.*;
import com.example.mrrag.service.graph.AstGraphI;
import com.example.mrrag.service.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.service.source.ProjectSource;
import com.example.mrrag.service.source.ProjectSourceProvider;
import com.example.mrrag.service.dto.ProjectSourceDto;
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
 * Primary implementation of {@link AstGraphI}.
 *
 * <p>Builds an in-memory AST graph for a Java project using
 * <a href="https://spoon.gforge.inria.fr/">Spoon</a>. The graph captures
 * class hierarchy, method calls, field usages, and annotation relationships
 * so that downstream components (review enrichment, RAG context) can reason
 * about code structure.
 */
@Slf4j
@Service
public class AstGraphService implements AstGraphI {

    // ------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------

    private final EdgeKindConfig         edgeConfig;
    private final GraphCacheProperties   cacheProps;
    private final ProjectGraphCacheStore cacheStore;

    // ------------------------------------------------------------------
    // Caches
    // ------------------------------------------------------------------

    /** Cache keyed by provider's projectId. */
    private final Map<String, ProjectGraph> projectIdCache = new ConcurrentHashMap<>();
    /** Cache keyed by ProjectKey (fingerprint-based). */
    private final Map<ProjectKey, ProjectGraph> keyCache   = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

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
    // AstGraphI — primary entry point (ProjectSourceDto)
    // ------------------------------------------------------------------

    @Override
    public ProjectGraph buildGraph(ProjectSourceDto dto) throws Exception {
        String pid = dto.projectId();
        if (pid != null && !pid.isBlank()) {
            ProjectGraph cached = projectIdCache.get(pid);
            if (cached != null) {
                log.debug("AST graph cache hit for projectId='{}'", pid);
                return cached;
            }
        }
        log.info("Building AST graph via dto: projectId='{}'", pid);
        ProjectGraph graph = doBuildGraphFromSources(dto.sources(), dto.classpathRoot());
        if (pid != null && !pid.isBlank()) {
            projectIdCache.put(pid, graph);
        }
        return graph;
    }

    // ------------------------------------------------------------------
    // AstGraphI — provider-based entry point
    // ------------------------------------------------------------------

    /**
     * Build a symbol graph from any {@link ProjectSourceProvider}.
     * Cached by {@link ProjectSourceProvider#projectId()} when non-blank.
     */
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
        }
        return graph;
    }

    // ------------------------------------------------------------------
    // AstGraphI — ProjectKey-based entry point (fingerprint cache)
    // ------------------------------------------------------------------

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

    public ProjectKey projectKey(Path projectRoot) {
        return new ProjectKey(projectRoot, ProjectFingerprint.compute(projectRoot));
    }

    // ------------------------------------------------------------------
    // AstGraphI — cache invalidation
    // ------------------------------------------------------------------

    @Override
    public void invalidate(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            projectIdCache.remove(projectId);
            log.info("AST graph cache evicted for projectId='{}'", projectId);
        }
    }

    // ------------------------------------------------------------------
    // AstGraphI — path normalisation
    // ------------------------------------------------------------------

    @Override
    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String norm = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (norm.equals(knownNorm))           return known;
            if (norm.endsWith("/" + knownNorm))   return known;
            if (knownNorm.endsWith("/" + norm))   return known;
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
    // Statistics helper
    // ------------------------------------------------------------------

    public GraphBuildStats stats(ProjectGraph graph) {
        GraphBuildStats s = new GraphBuildStats();
        s.fileCount = graph.byFile.size();
        s.nodeCount = graph.byFile.values().stream().mapToInt(List::size).sum();
        s.edgeCount = graph.edges.size();
        return s;
    }

    // ------------------------------------------------------------------
    // Core graph construction
    // ------------------------------------------------------------------

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

    private void trySetClasspath(Launcher launcher, Path projectRoot) {
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
        Map<String, GraphNode> nodeById = new LinkedHashMap<>();

        // Pass 1: create nodes
        for (CtType<?> type : model.getAllTypes()) {
            String filePath = sourcePathOf(type, sources);
            if (filePath == null) continue;

            GraphNode node = toNode(type, filePath);
            nodeById.put(node.id, node);
            graph.byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(node);

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

            if (type.getSuperclass() != null) {
                GraphNode superNode = nodeById.get(type.getSuperclass().getQualifiedName());
                if (superNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.EXTENDS, typeNode, superNode);
                    typeNode.edges.add(e); graph.edges.add(e);
                }
            }
            for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                GraphNode ifaceNode = nodeById.get(iface.getQualifiedName());
                if (ifaceNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.IMPLEMENTS, typeNode, ifaceNode);
                    typeNode.edges.add(e); graph.edges.add(e);
                }
            }
            for (CtAnnotation<?> ann : type.getAnnotations()) {
                GraphNode annNode = nodeById.get(ann.getAnnotationType().getQualifiedName());
                if (annNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.ANNOTATED_BY, typeNode, annNode);
                    typeNode.edges.add(e); graph.edges.add(e);
                }
            }
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

            if (method.getType() != null) {
                GraphNode retNode = nodeById.get(method.getType().getQualifiedName());
                if (retNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_RETURN_TYPE, methodNode, retNode);
                    methodNode.edges.add(e); graph.edges.add(e);
                }
            }
            for (CtParameter<?> param : method.getParameters()) {
                GraphNode pNode = nodeById.get(param.getType().getQualifiedName());
                if (pNode != null) {
                    GraphEdge e = new GraphEdge(EdgeKind.HAS_PARAM_TYPE, methodNode, pNode);
                    methodNode.edges.add(e); graph.edges.add(e);
                }
            }
            Set<String> visited = new HashSet<>();
            for (CtInvocation<?> inv : method.getElements(new TypeFilter<>(CtInvocation.class))) {
                try {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    if (exec.getDeclaringType() == null) continue;
                    String targetId = exec.getDeclaringType().getQualifiedName() + "#" + exec.getSimpleName();
                    if (visited.add(targetId)) {
                        GraphNode tgt = nodeById.get(targetId);
                        if (tgt != null) {
                            GraphEdge e = new GraphEdge(EdgeKind.CALLS, methodNode, tgt);
                            methodNode.edges.add(e); graph.edges.add(e);
                        }
                    }
                } catch (Exception ignored) { }
            }
            for (CtFieldRead<?> fr : method.getElements(new TypeFilter<>(CtFieldRead.class))) {
                try {
                    CtFieldReference<?> ref = fr.getVariable();
                    if (ref.getDeclaringType() == null) continue;
                    String fieldId = ref.getDeclaringType().getQualifiedName() + "." + ref.getSimpleName();
                    GraphNode fNode = nodeById.get(fieldId);
                    if (fNode != null) {
                        GraphEdge e = new GraphEdge(EdgeKind.USES_FIELD, methodNode, fNode);
                        methodNode.edges.add(e); graph.edges.add(e);
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
            case CtInterface<?> i      -> NodeKind.INTERFACE;
            case CtEnum<?> e           -> NodeKind.ENUM;
            case CtAnnotationType<?> a -> NodeKind.ANNOTATION_TYPE;
            default                    -> NodeKind.CLASS;
        };
        return new GraphNode(typeId(type), kind,
                type.getSimpleName(), type.getQualifiedName(),
                filePath, posLine(type), posEndLine(type));
    }

    private GraphNode methodNode(CtMethod<?> m, String filePath, CtType<?> owner) {
        String id = methodId(m, owner);
        return new GraphNode(id, NodeKind.METHOD, m.getSimpleName(), id,
                filePath, posLine(m), posEndLine(m));
    }

    private GraphNode fieldNode(CtField<?> f, String filePath, CtType<?> owner) {
        String id = owner.getQualifiedName() + "." + f.getSimpleName();
        return new GraphNode(id, NodeKind.FIELD, f.getSimpleName(), id,
                filePath, posLine(f), posEndLine(f));
    }

    private GraphNode constructorNode(CtConstructor<?> c, String filePath, CtType<?> owner) {
        String id = owner.getQualifiedName() + "#<init>";
        return new GraphNode(id, NodeKind.CONSTRUCTOR, "<init>", id,
                filePath, posLine(c), posEndLine(c));
    }

    private static String typeId(CtType<?> type)   { return type.getQualifiedName(); }
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
                String fileName = pos.getFile().getName();
                for (ProjectSource src : sources) {
                    String sp = src.path().replace('\\', '/');
                    if (sp.endsWith("/" + fileName) || sp.equals(fileName)) return src.path();
                }
            }
        } catch (Exception ignored) { }
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
}
