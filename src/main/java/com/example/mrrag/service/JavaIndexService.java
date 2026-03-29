package com.example.mrrag.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds and queries a symbol index for a Java project checked out locally.
 * Indexes: methods (with body hash), fields, local variables, method call
 * sites, field accesses, argument usages, and generic name usages.
 */
@Slf4j
@Service
public class JavaIndexService {

    private final Map<Path, ProjectIndex> indexCache = new ConcurrentHashMap<>();

    public ProjectIndex buildIndex(Path projectRoot) throws IOException {
        return indexCache.computeIfAbsent(projectRoot, root -> {
            try {
                return doBuildIndex(root);
            } catch (IOException e) {
                throw new RuntimeException("Failed to build index for " + root, e);
            }
        });
    }

    public void invalidate(Path projectRoot) {
        indexCache.remove(projectRoot);
    }

    // -----------------------------------------------------------------------
    // Index building
    // -----------------------------------------------------------------------

    private ProjectIndex doBuildIndex(Path projectRoot) throws IOException {
        log.info("Building JavaParser index for {}", projectRoot);
        List<Path> sourceRoots = findSourceRoots(projectRoot);

        var typeSolver = new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver();
        typeSolver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(false));
        for (Path src : sourceRoots) {
            typeSolver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(src.toFile()));
        }

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new com.github.javaparser.symbolsolver.JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        ProjectIndex index = new ProjectIndex();

        try (Stream<Path> stream = Files.walk(projectRoot)) {
            List<Path> javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/target/"))
                    .toList();

            log.info("Parsing {} Java files", javaFiles.size());
            for (Path file : javaFiles) {
                try {
                    var res = parser.parse(file);
                    if (res.isSuccessful() && res.getResult().isPresent()) {
                        indexFile(file, projectRoot, res.getResult().get(), index);
                    } else {
                        log.debug("Parse issues in {}: {}", file, res.getProblems());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse {}: {}", file, e.getMessage());
                }
            }
        }

        log.info("Index built: {} methods, {} fields, {} variables, {} call sites, {} field accesses",
                index.methods.size(), index.fields.size(), index.variables.size(),
                index.callSites.size(), index.fieldAccesses.size());
        return index;
    }

    private void indexFile(Path file, Path projectRoot, CompilationUnit cu, ProjectIndex index) {
        String relPath = projectRoot.relativize(file).toString();

        // --- Method declarations ---
        cu.findAll(MethodDeclaration.class).forEach(method -> method.getRange().ifPresent(range -> {
            String key = buildMethodKey(method);
            String bodyHash = computeBodyHash(method);
            MethodInfo info = new MethodInfo(
                    method.getNameAsString(), key, relPath,
                    range.begin.line, range.end.line,
                    method.getSignature().asString(),
                    method.getParameters().stream()
                            .map(p -> p.getType().asString() + " " + p.getNameAsString())
                            .toList(),
                    bodyHash
            );
            index.methods.put(key, info);
            index.methodsByFile.computeIfAbsent(relPath, k -> new ArrayList<>()).add(info);
        }));

        // --- Field declarations ---
        cu.findAll(FieldDeclaration.class).forEach(field -> field.getRange().ifPresent(range ->
                field.getVariables().forEach(var -> {
                    String resolvedKey = resolveFieldKey(field, var);
                    FieldInfo info = new FieldInfo(
                            var.getNameAsString(),
                            resolvedKey,
                            relPath,
                            range.begin.line,
                            field.getElementType().asString()
                    );
                    index.fields.put(resolvedKey, info);
                    index.fieldsByName.computeIfAbsent(var.getNameAsString(), k -> new ArrayList<>()).add(info);
                })
        ));

        // --- Local variable declarations ---
        cu.findAll(VariableDeclarator.class).forEach(var -> var.getRange().ifPresent(range -> {
            if (var.getParentNode().map(p -> p instanceof FieldDeclaration).orElse(false)) return;
            String name = var.getNameAsString();
            VariableInfo info = new VariableInfo(
                    name, relPath, range.begin.line, var.getTypeAsString());
            index.variables.computeIfAbsent(relPath + "#" + name, k -> new ArrayList<>()).add(info);
        }));

        // --- Method call sites ---
        cu.findAll(MethodCallExpr.class).forEach(call -> call.getRange().ifPresent(range -> {
            String resolved = resolveCalledMethod(call);
            List<String> argTexts = call.getArguments().stream()
                    .map(com.github.javaparser.ast.Node::toString)
                    .toList();
            CallSite site = new CallSite(
                    call.getNameAsString(), relPath, range.begin.line, resolved, argTexts);
            String key = resolved != null ? resolved : "unresolved:" + call.getNameAsString();
            index.callSites.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
        }));

        // --- Field accesses (obj.field) ---
        cu.findAll(FieldAccessExpr.class).forEach(fa -> fa.getRange().ifPresent(range -> {
            String fieldName = fa.getNameAsString();
            String resolvedKey = resolveFieldAccess(fa);
            FieldAccess access = new FieldAccess(
                    fieldName, relPath, range.begin.line, resolvedKey);
            String key = resolvedKey != null ? resolvedKey : "unresolved:" + fieldName;
            index.fieldAccesses.computeIfAbsent(key, k -> new ArrayList<>()).add(access);
            index.fieldAccessesByName.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(access);
        }));

        // --- Generic name usages ---
        cu.findAll(NameExpr.class).forEach(name -> name.getRange().ifPresent(range -> {
            String resolved = resolveNameExpr(name);
            if (resolved != null) {
                index.nameUsages
                        .computeIfAbsent(resolved, k -> new ArrayList<>())
                        .add(new NameUsage(name.getNameAsString(), relPath, range.begin.line));
            }
            index.nameUsagesBySimpleName
                    .computeIfAbsent(name.getNameAsString(), k -> new ArrayList<>())
                    .add(new NameUsage(name.getNameAsString(), relPath, range.begin.line));
        }));
    }

    // -----------------------------------------------------------------------
    // Body hash
    // -----------------------------------------------------------------------

    /**
     * Computes a normalised MD5 hash of a method body:
     * <ol>
     *   <li>Take the method body source text via JavaParser's {@code toString()}
     *       (this includes the full body block).</li>
     *   <li>Strip all line comments ({@code // ...}) and block comments
     *       ({@code /* ... *\/}).</li>
     *   <li>Collapse all whitespace (spaces, tabs, newlines) to a single space
     *       and trim, so formatting differences are ignored.</li>
     *   <li>MD5-hash the resulting string (UTF-8).</li>
     * </ol>
     * Returns {@code null} if the method has no body (abstract / interface default).
     */
    private String computeBodyHash(MethodDeclaration method) {
        return method.getBody().map(body -> {
            // 1. Get normalised body text
            String text = body.toString();
            // 2. Remove block comments
            text = text.replaceAll("/\\*.*?\\*/", " ");
            // 3. Remove line comments
            text = text.replaceAll("//[^\\n]*", " ");
            // 4. Collapse whitespace
            text = text.replaceAll("\\s+", " ").trim();
            // 5. MD5
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }

    // -----------------------------------------------------------------------
    // Resolution helpers
    // -----------------------------------------------------------------------

    private String resolveCalledMethod(MethodCallExpr call) {
        try { return call.resolve().getQualifiedSignature(); } catch (Exception e) { return null; }
    }

    private String resolveNameExpr(NameExpr name) {
        try {
            ResolvedValueDeclaration r = name.resolve();
            return r.getType().describe() + ":" + r.getName();
        } catch (Exception e) { return null; }
    }

    private String resolveFieldKey(FieldDeclaration field, VariableDeclarator var) {
        try {
            ResolvedFieldDeclaration r = field.resolve();
            return r.declaringType().getQualifiedName() + "." + var.getNameAsString();
        } catch (Exception e) {
            return field.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString() + "." + var.getNameAsString())
                    .orElse("?." + var.getNameAsString());
        }
    }

    private String resolveFieldAccess(FieldAccessExpr fa) {
        try {
            var r = fa.resolve();
            if (r instanceof ResolvedFieldDeclaration rfd) {
                return rfd.declaringType().getQualifiedName() + "." + rfd.getName();
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private String buildMethodKey(MethodDeclaration method) {
        try { return method.resolve().getQualifiedSignature(); }
        catch (Exception e) {
            return method.getNameAsString() + "(" +
                    method.getParameters().stream()
                            .map(p -> p.getType().asString())
                            .collect(Collectors.joining(",")) + ")";
        }
    }

    // -----------------------------------------------------------------------
    // Public query API
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Source root detection
    // -----------------------------------------------------------------------

    private List<Path> findSourceRoots(Path projectRoot) throws IOException {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java"),
                projectRoot
        );
        List<Path> existing = candidates.stream().filter(Files::isDirectory).toList();
        return existing.isEmpty() ? List.of(projectRoot) : existing;
    }

    // -----------------------------------------------------------------------
    // Data model
    // -----------------------------------------------------------------------

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

    /**
     * @param bodyHash MD5 of normalised method body (whitespace + comments stripped).
     *                 {@code null} for abstract methods and interface methods without
     *                 a default body.
     */
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
