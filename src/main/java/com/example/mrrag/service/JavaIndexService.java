package com.example.mrrag.service;

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

    private final GraphBuildService graphBuildService;
    private final GitLabApi         gitLabApi;

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /** Build from a locally cloned repo directory (original behaviour). */
    public ProjectIndex buildIndex(Path projectRoot) throws Exception {
        ProjectSourceProvider provider = new LocalCloneProjectSourceProvider(projectRoot);
        AstGraphService.ProjectGraph g = graphBuildService.buildGraph(provider);
        return project(g);
    }

    /**
     * Build from GitLab API without cloning.
     * {@code ref} accepts a branch name, tag, or <strong>commit SHA</strong>.
     */
    public ProjectIndex buildIndexFromRef(long projectId, String ref) throws Exception {
        return buildIndexFromProvider(new GitLabProjectSourceProvider(gitLabApi, projectId, ref));
    }

    /**
     * Generic build path: delegate source loading to any {@link ProjectSourceProvider}.
     */
    public ProjectIndex buildIndexFromProvider(ProjectSourceProvider provider) throws Exception {
        log.info("Building index via provider: {}", provider.getClass().getSimpleName());
        AstGraphService.ProjectGraph graph = graphBuildService.buildGraph(provider);
        return project(graph);
    }

    public void invalidate(Path projectRoot) {
        graphBuildService.invalidate(new LocalCloneProjectSourceProvider(projectRoot).projectKey());
    }

    // ------------------------------------------------------------------
    // Graph (raw)
    // ------------------------------------------------------------------

    public AstGraphService.ProjectGraph getGraph(Path projectRoot) throws Exception {
        return graphBuildService.buildGraph(new LocalCloneProjectSourceProvider(projectRoot));
    }

    public AstGraphService.ProjectGraph getGraphFromRef(long projectId, String ref) throws Exception {
        return graphBuildService.buildGraph(
                new GitLabProjectSourceProvider(gitLabApi, projectId, ref));
    }

    public AstGraphService.ProjectGraph getGraphFromProvider(ProjectSourceProvider provider)
            throws Exception {
        return graphBuildService.buildGraph(provider);
    }

    // ------------------------------------------------------------------
    // Projection: ProjectGraph → ProjectIndex
    // ------------------------------------------------------------------

    private ProjectIndex project(AstGraphService.ProjectGraph g) {
        ProjectIndex idx = new ProjectIndex();

        for (AstGraphService.GraphNode node : g.nodes.values()) {
            switch (node.kind()) {
                case METHOD -> {
                    String sig = extractSignatureFromId(node.id());
                    MethodInfo m = new MethodInfo(
                            node.simpleName(), node.id(), node.filePath(),
                            node.startLine(), node.endLine(), sig, List.of(), null);
                    idx.methods.put(node.id(), m);
                    idx.methodsByFile
                            .computeIfAbsent(node.filePath(), k -> new ArrayList<>()).add(m);
                }
                case FIELD -> {
                    FieldInfo f = new FieldInfo(
                            node.simpleName(), node.id(), node.filePath(),
                            node.startLine(), "");
                    idx.fields.put(node.id(), f);
                    idx.fieldsByName
                            .computeIfAbsent(node.simpleName(), k -> new ArrayList<>()).add(f);
                }
                case VARIABLE -> {
                    VariableInfo v = new VariableInfo(
                            node.simpleName(), node.filePath(), node.startLine(), "");
                    idx.variables
                            .computeIfAbsent(node.filePath() + "#" + node.simpleName(),
                                    k -> new ArrayList<>()).add(v);
                }
                default -> {}
            }
        }

        for (List<AstGraphService.GraphEdge> edges : g.edgesFrom.values()) {
            for (AstGraphService.GraphEdge e : edges) {
                if (e.kind() == AstGraphService.EdgeKind.INVOKES) {
                    CallSite cs = new CallSite(
                            simpleNameFromId(e.callee()), e.filePath(), e.line(),
                            e.callee(), List.of());
                    idx.callSites
                            .computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(cs);
                }
            }
        }

        for (List<AstGraphService.GraphEdge> edges : g.edgesFrom.values()) {
            for (AstGraphService.GraphEdge e : edges) {
                if (e.kind() == AstGraphService.EdgeKind.READS_FIELD
                        || e.kind() == AstGraphService.EdgeKind.WRITES_FIELD) {
                    FieldAccess fa = new FieldAccess(
                            simpleNameFromFieldId(e.callee()), e.filePath(), e.line(), e.callee());
                    idx.fieldAccesses
                            .computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(fa);
                    idx.fieldAccessesByName
                            .computeIfAbsent(fa.fieldName(), k -> new ArrayList<>()).add(fa);
                }
            }
        }

        for (List<AstGraphService.GraphEdge> edges : g.edgesFrom.values()) {
            for (AstGraphService.GraphEdge e : edges) {
                if (e.kind() == AstGraphService.EdgeKind.READS_LOCAL_VAR
                        || e.kind() == AstGraphService.EdgeKind.WRITES_LOCAL_VAR) {
                    String name = simpleNameFromVarId(e.callee());
                    NameUsage nu = new NameUsage(name, e.filePath(), e.line());
                    idx.nameUsages
                            .computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(nu);
                    idx.nameUsagesBySimpleName
                            .computeIfAbsent(name, k -> new ArrayList<>()).add(nu);
                }
            }
        }

        log.info("Projected index: {} methods, {} fields, {} variables, {} call-site keys",
                idx.methods.size(), idx.fields.size(), idx.variables.size(), idx.callSites.size());
        return idx;
    }

    // ------------------------------------------------------------------
    // ID parsing helpers
    // ------------------------------------------------------------------

    private static String extractSignatureFromId(String id) {
        int hash = id.lastIndexOf('#');
        return hash >= 0 ? id.substring(hash + 1) : id;
    }

    private static String simpleNameFromId(String id) {
        int hash = id.lastIndexOf('#');
        String sig = hash >= 0 ? id.substring(hash + 1) : id;
        int paren = sig.indexOf('(');
        return paren >= 0 ? sig.substring(0, paren) : sig;
    }

    private static String simpleNameFromFieldId(String id) {
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    private static String simpleNameFromVarId(String id) {
        int colon = id.lastIndexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
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

    public List<NameUsage> findUsages(ProjectIndex index, String resolvedKey) {
        return index.nameUsages.getOrDefault(resolvedKey, List.of());
    }

    public List<NameUsage> findUsagesByName(ProjectIndex index, String simpleName) {
        return index.nameUsagesBySimpleName.getOrDefault(simpleName, List.of());
    }

    public Optional<MethodInfo> findMethod(ProjectIndex index, String qualifiedSignature) {
        return Optional.ofNullable(index.methods.get(qualifiedSignature));
    }

    public List<MethodInfo> findMethodsInRange(ProjectIndex index, String relPath,
                                               int fromLine, int toLine) {
        return index.methodsByFile.getOrDefault(relPath, List.of()).stream()
                .filter(m -> m.startLine() <= toLine && m.endLine() >= fromLine)
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

    public List<CallSite> findCallSitesWithArgument(ProjectIndex index, String methodKey,
                                                    String argName) {
        return findCallSites(index, methodKey).stream()
                .filter(cs -> cs.argumentTexts().stream().anyMatch(a -> a.contains(argName)))
                .toList();
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    public static class ProjectIndex {
        public final Map<String, MethodInfo>           methods             = new LinkedHashMap<>();
        public final Map<String, List<MethodInfo>>     methodsByFile       = new LinkedHashMap<>();
        public final Map<String, FieldInfo>            fields              = new LinkedHashMap<>();
        public final Map<String, List<FieldInfo>>      fieldsByName        = new LinkedHashMap<>();
        public final Map<String, List<VariableInfo>>   variables           = new LinkedHashMap<>();
        public final Map<String, List<CallSite>>       callSites           = new LinkedHashMap<>();
        public final Map<String, List<FieldAccess>>    fieldAccesses       = new LinkedHashMap<>();
        public final Map<String, List<FieldAccess>>    fieldAccessesByName = new LinkedHashMap<>();
        public final Map<String, List<NameUsage>>      nameUsages          = new LinkedHashMap<>();
        public final Map<String, List<NameUsage>>      nameUsagesBySimpleName = new LinkedHashMap<>();
    }

    public record MethodInfo(
            String name, String qualifiedKey, String filePath,
            int startLine, int endLine, String signature,
            List<String> parameters, String bodyHash) {}

    public record FieldInfo(
            String name, String resolvedKey, String filePath,
            int declarationLine, String type) {}

    public record VariableInfo(
            String name, String filePath, int declarationLine, String type) {}

    public record CallSite(
            String methodName, String filePath, int line,
            String resolvedKey, List<String> argumentTexts) {}

    public record FieldAccess(
            String fieldName, String filePath, int line, String resolvedKey) {}

    public record NameUsage(
            String name, String filePath, int line) {}
}
