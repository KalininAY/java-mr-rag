package com.example.mrrag.service;

import com.example.mrrag.model.graph.EdgeKind;
import com.example.mrrag.model.graph.GraphEdge;
import com.example.mrrag.model.graph.GraphNode;
import com.example.mrrag.model.graph.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backward-compatible index API used by {@link ContextEnricher}.
 * <p>
 * Delegates AST graph construction to {@link AstGraphService} and projects the
 * rich {@link ProjectGraph} into the flat maps that the rest of
 * the application expects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JavaIndexService {

    private final AstGraphProvider graphService;
    private final SourceProvider sourceProvider;
    private final Map<Path, ProjectIndex> indexCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Public build / invalidate
    // ------------------------------------------------------------------

    public ProjectIndex buildIndex(Path projectRoot) {
        return indexCache.computeIfAbsent(projectRoot, root -> {
            List<String> sources = sourceProvider.sourceProvider(root);
            ProjectGraph g = graphService.buildGraph(projectRoot.toString(), sources);
            return project(g, root);
        });
    }

    public void invalidate(Path projectRoot) {
        indexCache.remove(projectRoot);
        graphService.invalidate(projectRoot.toString());
    }

    /**
     * Returns the raw Spoon graph for callers that need more than the flat index.
     */
    public ProjectGraph getGraph(Path projectRoot) {
        List<String> sources = sourceProvider.sourceProvider(projectRoot);
        return graphService.buildGraph(projectRoot.toString(), sources);
    }

    // ------------------------------------------------------------------
    // Projection: ProjectGraph → ProjectIndex
    // ------------------------------------------------------------------

    private ProjectIndex project(ProjectGraph g, Path root) {
        ProjectIndex idx = new ProjectIndex();

        for (GraphNode node : g.nodes.values()) {
            switch (node.kind()) {
                case METHOD -> {
                    // Reconstruct a MethodInfo from graph node + incoming CONTAINS edge
                    // (name, key, file, lines, signature derived from id)
                    String sig = extractSignatureFromId(node.id());
                    MethodInfo m = new MethodInfo(
                            node.simpleName(),
                            node.id(),
                            node.filePath(),
                            node.startLine(),
                            node.endLine(),
                            sig,
                            List.of(), // params: full list available from Spoon node via graph
                            null        // bodyHash: expensive, computed on demand
                    );
                    idx.methods.put(node.id(), m);
                    idx.methodsByFile
                            .computeIfAbsent(node.filePath(), k -> new ArrayList<>()).add(m);
                }
                case FIELD -> {
                    FieldInfo f = new FieldInfo(
                            node.simpleName(), node.id(), node.filePath(),
                            node.startLine(), "" /* type not stored in node */);
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
                default -> { /* CLASS, LAMBDA, ANNOTATION — not in flat index */ }
            }
        }

        // Rebuild call sites from INVOKES edges
        for (List<GraphEdge> edges : g.edgesFrom.values()) {
            for (GraphEdge e : edges) {
                if (e.kind() == EdgeKind.INVOKES) {
                    CallSite cs = new CallSite(
                            simpleNameFromId(e.callee()), e.filePath(), e.line(),
                            e.callee(), List.of());
                    idx.callSites
                            .computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(cs);
                }
            }
        }

        // Rebuild field accesses from READS_FIELD / WRITES_FIELD edges
        for (List<GraphEdge> edges : g.edgesFrom.values()) {
            for (GraphEdge e : edges) {
                if (e.kind() == EdgeKind.READS_FIELD
                        || e.kind() == EdgeKind.WRITES_FIELD) {
                    FieldAccess fa = new FieldAccess(
                            simpleNameFromFieldId(e.callee()), e.filePath(), e.line(), e.callee());
                    idx.fieldAccesses
                            .computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(fa);
                    idx.fieldAccessesByName
                            .computeIfAbsent(fa.fieldName(), k -> new ArrayList<>()).add(fa);
                }
            }
        }

        // Rebuild name usages from READS_VAR / WRITES_VAR edges
        for (List<GraphEdge> edges : g.edgesFrom.values()) {
            for (GraphEdge e : edges) {
                if (e.kind() == EdgeKind.READS_LOCAL_VAR
                        || e.kind() == EdgeKind.WRITES_LOCAL_VAR) {
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

    /** "com.example.Foo#bar(int, String)"  →  "bar(int, String)" */
    private static String extractSignatureFromId(String id) {
        int hash = id.lastIndexOf('#');
        return hash >= 0 ? id.substring(hash + 1) : id;
    }

    /** "com.example.Foo#bar(int)" → "bar" */
    private static String simpleNameFromId(String id) {
        int hash = id.lastIndexOf('#');
        String sig = hash >= 0 ? id.substring(hash + 1) : id;
        int paren = sig.indexOf('(');
        return paren >= 0 ? sig.substring(0, paren) : sig;
    }

    /** "com.example.Foo.myField" → "myField" */
    private static String simpleNameFromFieldId(String id) {
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    /** "var@Foo.java:42:count" → "count" */
    private static String simpleNameFromVarId(String id) {
        int colon = id.lastIndexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ------------------------------------------------------------------
    // Public query API (unchanged surface — ContextEnricher uses these)
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

    public List<MethodInfo> findMethodsInRange(ProjectIndex index, String relPath, int fromLine, int toLine) {
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

    public List<CallSite> findCallSitesWithArgument(ProjectIndex index, String methodKey, String argName) {
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
            List<String> parameters,
            String bodyHash
    ) {}

    public record FieldInfo(
            String name, String resolvedKey, String filePath,
            int declarationLine, String type
    ) {}

    public record VariableInfo(
            String name, String filePath, int declarationLine, String type
    ) {}

    public record CallSite(
            String methodName, String filePath, int line,
            String resolvedKey, List<String> argumentTexts
    ) {}

    public record FieldAccess(
            String fieldName, String filePath, int line, String resolvedKey
    ) {}

    public record NameUsage(
            String name, String filePath, int line
    ) {}
}
