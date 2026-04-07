package com.example.mrrag.service;

import com.example.mrrag.service.graph.GraphBuildService;
import com.example.mrrag.service.source.GitLabProjectSourceProvider;
import com.example.mrrag.service.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.service.source.ProjectSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backward-compatible index API used by {@link ContextEnricher}.
 *
 * <h2>Build modes</h2>
 * <ul>
 *   <li>{@link #buildIndex(Path)} — local clone (original behaviour).</li>
 *   <li>{@link #buildIndexFromRef(long, String)} — no-clone via GitLab API.</li>
 *   <li>{@link #buildIndexFromProvider(ProjectSourceProvider)} — generic: supply
 *       any {@link ProjectSourceProvider} implementation.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JavaIndexService {

    private final GraphBuildService graphService;
    private final GitLabApi         gitLabApi;

    private final Map<Path, ProjectIndex> indexCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /** Build from a locally cloned repo directory (original behaviour). */
    public ProjectIndex buildIndex(Path projectRoot) {
        return indexCache.computeIfAbsent(projectRoot, root -> {
            try {
                return buildIndexFromProvider(new LocalCloneProjectSourceProvider(root));
            } catch (Exception e) {
                throw new RuntimeException("Failed to build index for " + root, e);
            }
        });
    }

    /**
     * Build from GitLab API without cloning.
     *
     * <p>{@code ref} accepts a branch name, tag, or <strong>commit SHA</strong>.
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch, tag, or commit SHA
     */
    public ProjectIndex buildIndexFromRef(long projectId, String ref) throws Exception {
        return buildIndexFromProvider(new GitLabProjectSourceProvider(gitLabApi, projectId, ref));
    }

    /**
     * Generic build path: delegate source loading to any {@link ProjectSourceProvider}.
     */
    public ProjectIndex buildIndexFromProvider(ProjectSourceProvider provider) throws Exception {
        log.info("Building index via provider: {}", provider.getClass().getSimpleName());
        AstGraphService.ProjectGraph graph = graphService.buildGraph(provider);
        return project(graph);
    }

    public void invalidate(Path projectRoot) {
        indexCache.remove(projectRoot);
        graphService.invalidate(projectRoot);
    }

    // ------------------------------------------------------------------
    // Graph (raw)
    // ------------------------------------------------------------------

    /** Raw graph from local clone. */
    public AstGraphService.ProjectGraph getGraph(Path projectRoot) {
        return graphService.buildGraph(projectRoot);
    }

    /**
     * Raw graph from GitLab API (no-clone).
     *
     * @param projectId numeric GitLab project id
     * @param ref       branch, tag, or commit SHA
     */
    public AstGraphService.ProjectGraph getGraphFromRef(long projectId, String ref) throws Exception {
        return graphService.buildGraph(new GitLabProjectSourceProvider(gitLabApi, projectId, ref));
    }

    /** Raw graph via any provider. */
    public AstGraphService.ProjectGraph getGraphFromProvider(ProjectSourceProvider provider) throws Exception {
        return graphService.buildGraph(provider);
    }

    // ------------------------------------------------------------------
    // Projection: ProjectGraph -> ProjectIndex
    // ------------------------------------------------------------------

    /**
     * Projects a {@link AstGraphService.ProjectGraph} into a flat {@link ProjectIndex}
     * suitable for line-based lookups.
     *
     * <p>Maps the new graph model (byFile/edges with public fields) onto the
     * index data structures used by {@link ContextEnricher} and the REST API.
     */
    private ProjectIndex project(AstGraphService.ProjectGraph graph) {
        ProjectIndex idx = new ProjectIndex();

        // --- Nodes ---
        for (List<AstGraphService.GraphNode> fileNodes : graph.byFile.values()) {
            for (AstGraphService.GraphNode node : fileNodes) {
                switch (node.kind) {
                    case METHOD -> {
                        String sig = extractSignatureFromId(node.id);
                        MethodInfo m = new MethodInfo(
                                node.name, node.id, node.filePath,
                                node.lineStart, node.lineEnd, sig, List.of(), null);
                        idx.methods.put(node.id, m);
                        idx.methodsByFile
                                .computeIfAbsent(node.filePath, k -> new ArrayList<>())
                                .add(m);
                    }
                    case FIELD -> {
                        FieldInfo f = new FieldInfo(
                                node.name, node.id, node.filePath, node.lineStart, "");
                        idx.fields.put(node.id, f);
                        idx.fieldsByName
                                .computeIfAbsent(node.name, k -> new ArrayList<>())
                                .add(f);
                    }
                    default -> { /* CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, CONSTRUCTOR — not indexed */ }
                }
            }
        }

        // --- Edges ---
        for (AstGraphService.GraphEdge e : graph.edges) {
            if (e.kind == AstGraphService.EdgeKind.CALLS) {
                String calledId   = e.to.id;
                String callerFile = e.from.filePath;
                int    callerLine = e.from.lineStart;
                CallSite cs = new CallSite(
                        simpleNameFromId(calledId), callerFile,
                        callerLine, calledId, List.of());
                idx.callSites
                        .computeIfAbsent(calledId, k -> new ArrayList<>())
                        .add(cs);
            } else if (e.kind == AstGraphService.EdgeKind.USES_FIELD) {
                String fieldId   = e.to.id;
                String fieldName = simpleNameFromFieldId(fieldId);
                FieldAccess fa   = new FieldAccess(
                        fieldName, e.from.filePath, e.from.lineStart, fieldId);
                idx.fieldAccesses
                        .computeIfAbsent(fieldId, k -> new ArrayList<>())
                        .add(fa);
                idx.fieldAccessesByName
                        .computeIfAbsent(fieldName, k -> new ArrayList<>())
                        .add(fa);
            }
        }

        log.info("Projected index: {} methods, {} fields, {} call-site keys, {} field-access keys",
                idx.methods.size(), idx.fields.size(),
                idx.callSites.size(), idx.fieldAccesses.size());
        return idx;
    }

    // ------------------------------------------------------------------
    // ID parsing helpers
    // ------------------------------------------------------------------

    private static String extractSignatureFromId(String id) {
        int h = id.lastIndexOf('#');
        return h >= 0 ? id.substring(h + 1) : id;
    }

    private static String simpleNameFromId(String id) {
        int h = id.lastIndexOf('#');
        String sig = h >= 0 ? id.substring(h + 1) : id;
        int p = sig.indexOf('(');
        return p >= 0 ? sig.substring(0, p) : sig;
    }

    private static String simpleNameFromFieldId(String id) {
        int d = id.lastIndexOf('.');
        return d >= 0 ? id.substring(d + 1) : id;
    }

    // ------------------------------------------------------------------
    // Query API
    // ------------------------------------------------------------------

    public Optional<MethodInfo> findContainingMethod(ProjectIndex index, String relPath, int line) {
        return index.methodsByFile.getOrDefault(relPath, List.of()).stream()
                .filter(m -> line >= m.startLine() && line <= m.endLine())
                .findFirst();
    }

    public List<CallSite> findCallSites(ProjectIndex index, String methodKey) {
        return index.callSites.getOrDefault(methodKey, List.of());
    }

    public Optional<MethodInfo> findMethod(ProjectIndex index, String qualifiedSignature) {
        return Optional.ofNullable(index.methods.get(qualifiedSignature));
    }

    public List<MethodInfo> findMethodsInRange(ProjectIndex index, String relPath, int from, int to) {
        return index.methodsByFile.getOrDefault(relPath, List.of()).stream()
                .filter(m -> m.startLine() <= to && m.endLine() >= from)
                .toList();
    }

    public List<FieldInfo> findFieldsByName(ProjectIndex index, String name) {
        return index.fieldsByName.getOrDefault(name, List.of());
    }

    public List<FieldAccess> findFieldAccesses(ProjectIndex index, String fieldKey) {
        return index.fieldAccesses.getOrDefault(fieldKey, List.of());
    }

    public List<FieldAccess> findFieldAccessesByName(ProjectIndex index, String fieldName) {
        return index.fieldAccessesByName.getOrDefault(fieldName, List.of());
    }

    public List<CallSite> findCallSitesWithArgument(ProjectIndex index, String methodKey, String argName) {
        return findCallSites(index, methodKey).stream()
                .filter(cs -> cs.argumentTexts().stream().anyMatch(a -> a.contains(argName)))
                .toList();
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    public static class ProjectIndex {
        public final Map<String, MethodInfo>        methods             = new LinkedHashMap<>();
        public final Map<String, List<MethodInfo>>  methodsByFile       = new LinkedHashMap<>();
        public final Map<String, FieldInfo>         fields              = new LinkedHashMap<>();
        public final Map<String, List<FieldInfo>>   fieldsByName        = new LinkedHashMap<>();
        public final Map<String, List<CallSite>>    callSites           = new LinkedHashMap<>();
        public final Map<String, List<FieldAccess>> fieldAccesses       = new LinkedHashMap<>();
        public final Map<String, List<FieldAccess>> fieldAccessesByName = new LinkedHashMap<>();
    }

    public record MethodInfo(String name, String qualifiedKey, String filePath,
            int startLine, int endLine, String signature,
            List<String> parameters, String bodyHash) {}

    public record FieldInfo(String name, String resolvedKey, String filePath,
            int declarationLine, String type) {}

    public record CallSite(String methodName, String filePath, int line,
            String resolvedKey, List<String> argumentTexts) {}

    public record FieldAccess(String fieldName, String filePath, int line, String resolvedKey) {}
}
