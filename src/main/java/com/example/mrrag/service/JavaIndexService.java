package com.example.mrrag.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds and queries a symbol index for a Java project checked out locally.
 * Uses JavaParser + SymbolSolver to resolve method/field/variable declarations.
 */
@Slf4j
@Service
public class JavaIndexService {

    // Cache: projectDir -> index
    private final Map<Path, ProjectIndex> indexCache = new ConcurrentHashMap<>();

    /**
     * Build (or return cached) index for the given project root.
     */
    public ProjectIndex buildIndex(Path projectRoot) throws IOException {
        return indexCache.computeIfAbsent(projectRoot, root -> {
            try {
                return doBuildIndex(root);
            } catch (IOException e) {
                throw new RuntimeException("Failed to build index for " + root, e);
            }
        });
    }

    /** Invalidate cache for a project (call when branch changes). */
    public void invalidate(Path projectRoot) {
        indexCache.remove(projectRoot);
    }

    // -----------------------------------------------------------------------
    // Index building
    // -----------------------------------------------------------------------

    private ProjectIndex doBuildIndex(Path projectRoot) throws IOException {
        log.info("Building JavaParser index for {}", projectRoot);

        // Find source roots (src/main/java, src/test/java, or root itself)
        List<Path> sourceRoots = findSourceRoots(projectRoot);
        log.debug("Source roots: {}", sourceRoots);

        // Configure SymbolSolver
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        for (Path src : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(src.toFile()));
        }

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        ProjectIndex index = new ProjectIndex();

        // Parse all .java files
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            List<Path> javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/target/"))
                    .toList();

            log.info("Parsing {} Java files", javaFiles.size());
            for (Path file : javaFiles) {
                try {
                    var parseResult = parser.parse(file);
                    if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                        indexFile(file, projectRoot, parseResult.getResult().get(), index);
                    } else {
                        log.debug("Parse issues in {}: {}", file, parseResult.getProblems());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse {}: {}", file, e.getMessage());
                }
            }
        }

        log.info("Index built: {} methods, {} fields, {} call sites",
                index.methods.size(), index.fields.size(), index.callSites.size());
        return index;
    }

    private void indexFile(Path file, Path projectRoot, CompilationUnit cu, ProjectIndex index) {
        String relPath = projectRoot.relativize(file).toString();

        // Index method declarations
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            method.getRange().ifPresent(range -> {
                String key = buildMethodKey(method);
                MethodInfo info = new MethodInfo(
                        method.getNameAsString(),
                        key,
                        relPath,
                        range.begin.line,
                        range.end.line,
                        method.getSignature().asString()
                );
                index.methods.put(key, info);
                index.methodsByFile
                        .computeIfAbsent(relPath, k -> new ArrayList<>())
                        .add(info);
            });
        });

        // Index field declarations
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            field.getRange().ifPresent(range -> {
                field.getVariables().forEach(var -> {
                    String key = relPath + "#" + var.getNameAsString();
                    FieldInfo info = new FieldInfo(
                            var.getNameAsString(),
                            relPath,
                            range.begin.line
                    );
                    index.fields.put(key, info);
                });
            });
        });

        // Index method call sites
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            call.getRange().ifPresent(range -> {
                CallSite site = new CallSite(
                        call.getNameAsString(),
                        relPath,
                        range.begin.line,
                        resolveCalledMethod(call)
                );
                index.callSites
                        .computeIfAbsent(site.resolvedKey() != null ? site.resolvedKey() : "unresolved:" + site.methodName(), k -> new ArrayList<>())
                        .add(site);
            });
        });

        // Index variable / name references to track usages
        cu.findAll(NameExpr.class).forEach(name -> {
            name.getRange().ifPresent(range -> {
                String resolved = resolveNameExpr(name);
                if (resolved != null) {
                    index.nameUsages
                            .computeIfAbsent(resolved, k -> new ArrayList<>())
                            .add(new NameUsage(name.getNameAsString(), relPath, range.begin.line));
                }
            });
        });
    }

    private String resolveCalledMethod(MethodCallExpr call) {
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            return resolved.getQualifiedSignature();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveNameExpr(NameExpr name) {
        try {
            ResolvedValueDeclaration resolved = name.resolve();
            return resolved.getType().describe() + ":" + resolved.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildMethodKey(MethodDeclaration method) {
        // Try to get fully qualified name from the resolved declaration
        try {
            return method.resolve().getQualifiedSignature();
        } catch (Exception e) {
            // Fallback to simple name with param types
            return method.getNameAsString() + "(" +
                    method.getParameters().stream()
                            .map(p -> p.getType().asString())
                            .collect(Collectors.joining(",")) + ")";
        }
    }

    // -----------------------------------------------------------------------
    // Querying
    // -----------------------------------------------------------------------

    /**
     * Find the method that contains the given line in the given file.
     */
    public Optional<MethodInfo> findContainingMethod(ProjectIndex index, String relPath, int line) {
        List<MethodInfo> methods = index.methodsByFile.getOrDefault(relPath, List.of());
        return methods.stream()
                .filter(m -> line >= m.startLine() && line <= m.endLine())
                .findFirst();
    }

    /**
     * Find all call sites for a given method key.
     */
    public List<CallSite> findCallSites(ProjectIndex index, String methodKey) {
        return index.callSites.getOrDefault(methodKey, List.of());
    }

    /**
     * Find all usages of a field or variable by its resolved key.
     */
    public List<NameUsage> findUsages(ProjectIndex index, String resolvedKey) {
        return index.nameUsages.getOrDefault(resolvedKey, List.of());
    }

    /**
     * Get method info by qualified signature.
     */
    public Optional<MethodInfo> findMethod(ProjectIndex index, String qualifiedSignature) {
        return Optional.ofNullable(index.methods.get(qualifiedSignature));
    }

    /**
     * Find methods declared in a file that overlap the given line range.
     */
    public List<MethodInfo> findMethodsInRange(ProjectIndex index, String relPath, int fromLine, int toLine) {
        return index.methodsByFile.getOrDefault(relPath, List.of()).stream()
                .filter(m -> m.startLine() <= toLine && m.endLine() >= fromLine)
                .toList();
    }

    // -----------------------------------------------------------------------
    // Source root detection
    // -----------------------------------------------------------------------

    private List<Path> findSourceRoots(Path projectRoot) throws IOException {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java"),
                projectRoot.resolve("src/main/kotlin"),
                projectRoot
        );
        List<Path> existing = candidates.stream()
                .filter(Files::isDirectory)
                .toList();
        return existing.isEmpty() ? List.of(projectRoot) : existing;
    }

    // -----------------------------------------------------------------------
    // Data classes
    // -----------------------------------------------------------------------

    public static class ProjectIndex {
        public final Map<String, MethodInfo> methods = new LinkedHashMap<>();
        public final Map<String, List<MethodInfo>> methodsByFile = new LinkedHashMap<>();
        public final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        public final Map<String, List<CallSite>> callSites = new LinkedHashMap<>();
        public final Map<String, List<NameUsage>> nameUsages = new LinkedHashMap<>();
    }

    public record MethodInfo(
            String name,
            String qualifiedKey,
            String filePath,
            int startLine,
            int endLine,
            String signature
    ) {}

    public record FieldInfo(
            String name,
            String filePath,
            int declarationLine
    ) {}

    public record CallSite(
            String methodName,
            String filePath,
            int line,
            String resolvedKey
    ) {}

    public record NameUsage(
            String name,
            String filePath,
            int line
    ) {}
}
