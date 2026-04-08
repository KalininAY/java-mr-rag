package com.example.mrrag.graph;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import spoon.reflect.code.*;
import spoon.reflect.cu.CompilationUnit;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.cu.position.BodyHolderSourcePosition;
import spoon.reflect.cu.position.CompoundSourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Utility class containing stateless helper methods extracted from
 * {@link GraphBuilderImpl} to keep the main service class focused on
 * orchestration rather than low-level details.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Spoon element ID computation (qualified names, executable IDs, etc.)</li>
 *   <li>Source snippet extraction from in-memory line arrays</li>
 *   <li>Position/path helpers (relPath, lines, posLine, sourceFile)</li>
 *   <li>Owner inference for invocations and constructor calls</li>
 *   <li>Semantic body comparison via {@link GraphNode#bodyHash()} (see {@link #sameBody})</li>
 * </ul>
 *
 * <p>All methods are {@code public static} — this class has no state and
 * should never be instantiated.
 */
public final class AstGraphUtils {

    private AstGraphUtils() {
        // utility class
    }

    // ------------------------------------------------------------------
    // Source snippet helpers
    // ------------------------------------------------------------------

    /**
     * Extracts source lines [{@code startLine}, {@code endLine}] (1-based, inclusive) from
     * {@code sourceLines}. Returns {@code ""} when {@code fileLines} cannot be resolved —
     * there is no reliable fallback if the original source is absent.
     *
     * @param projectRoot optional local project root for path resolution
     */
    public static String extractSource(Map<String, String[]> sourceLines,
                                       String filePath, int startLine, int endLine,
                                       CtElement el, Path projectRoot) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = findLines(sourceLines, filePath, projectRoot);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to   = Math.min(lines.length, endLine);
                if (from < to) return String.join("\n", Arrays.copyOfRange(lines, from, to));
            }
        }
        return ""; // нет исходного кода
    }

    /**
     * Resolves {@code sourceLines} lookup when keys are repo-relative (e.g. {@code src/main/java/Foo.java})
     * but {@code filePath} is absolute (Spoon positions), or when separator styles differ.
     */
    static String[] findLines(Map<String, String[]> sourceLines, String filePath, Path projectRoot) {
        if (filePath == null || filePath.isBlank()) return null;
        String[] lines = sourceLines.get(filePath);
        if (lines != null) return lines;
        try {
            String n = Path.of(filePath).normalize().toString();
            lines = sourceLines.get(n);
            if (lines != null) return lines;
        } catch (Exception ignored) { }
        lines = sourceLines.get(filePath.replace('\\', '/'));
        if (lines != null) return lines;
        lines = sourceLines.get(filePath.replace('/', '\\'));
        if (lines != null) return lines;
        if (projectRoot != null) {
            try {
                Path root = projectRoot.toAbsolutePath().normalize();
                Path abs = Path.of(filePath).toAbsolutePath().normalize();
                if (abs.startsWith(root)) {
                    Path rel = root.relativize(abs);
                    String key = rel.toString().replace('\\', '/');
                    lines = sourceLines.get(key);
                    if (lines != null) return lines;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Semantic body comparison (delegates to GraphNode field)
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} when both nodes are non-null and their
     * {@link GraphNode#bodyHash()} values match — i.e. the source bodies
     * are identical modulo whitespace normalisation.
     */
    public static boolean sameBody(GraphNode a, GraphNode b) {
        if (a == null || b == null) return false;
        return a.sameBodyAs(b);
    }

    // ------------------------------------------------------------------
    // ID helpers
    // ------------------------------------------------------------------

    public static String qualifiedName(CtType<?> type) {
        if (type == null) return null;
        String q = type.getQualifiedName();
        return (q == null || q.isBlank()) ? null : q;
    }

    public static String typeMemberExecId(CtTypeMember member) {
        if (member == null) return null;
        try {
            CtType<?> decl = member.getDeclaringType();
            String owner = decl != null ? decl.getQualifiedName() : "?";
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

    public static String constructorExecutableId(String ownerQualified, String signature) {
        if (signature == null || signature.isBlank()) {
            return ownerQualified + "#<init>()";
        }
        int open = signature.indexOf('(');
        if (open < 0) {
            return ownerQualified + "#<init>()";
        }
        return ownerQualified + "#<init>" + signature.substring(open);
    }

    public static String fieldId(CtField<?> field) {
        if (field.getDeclaringType() == null) return null;
        return field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName();
    }

    public static String varId(CtVariable<?> v) {
        if (!v.getPosition().isValidPosition()) return null;
        String file = v.getPosition().getFile() != null ? v.getPosition().getFile().getName() : "?";
        return "var@" + file + ":" + v.getPosition().getLine() + ":" + v.getSimpleName();
    }

    public static String varRefId(CtVariableReference<?> ref) {
        if (ref == null) return "var@?";
        try { CtVariable<?> d = ref.getDeclaration(); if (d != null) return varId(d); } catch (Exception ignored) {}
        return "var@" + ref.getSimpleName();
    }

    public static String typeParamId(String ownerId, CtTypeParameter tp) {
        return ownerId + "#<" + tp.getSimpleName() + ">";
    }

    public static String formalDeclarerId(CtFormalTypeDeclarer d) {
        if (d instanceof CtType<?> t) return qualifiedName(t);
        if (d instanceof CtTypeMember m) return typeMemberExecId(m);
        return null;
    }

    public static String nearestExecId(CtElement el) {
        CtMethod<?> m = el.getParent(CtMethod.class);
        if (m != null) return typeMemberExecId(m);
        CtConstructor<?> c = el.getParent(CtConstructor.class);
        return c != null ? typeMemberExecId(c) : null;
    }

    // ------------------------------------------------------------------
    // Executable reference ID helpers
    // ------------------------------------------------------------------

    public static String execRefId(CtExecutableReference<?> ref, CtElement useSite) {
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

    public static String execRefIdForChainedInvocation(CtInvocation<?> inv) {
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
        if (target != null) {
            try {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    if (isUsableQualifiedName(q)) {
                        return q + "#" + suffix;
                    }
                }
            } catch (Exception ignored) { }
        }
        return base;
    }

    // ------------------------------------------------------------------
    // Owner inference
    // ------------------------------------------------------------------

    public static String qualifiedExecutableOwner(CtExecutableReference<?> ref, CtElement useSite) {
        try {
            if (ref.getDeclaringType() != null) {
                String q = ref.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) { }
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl instanceof CtTypeMember tm && tm.getDeclaringType() != null) {
                String q = tm.getDeclaringType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) { }

        if (useSite instanceof CtInvocation inv) {
            String inferred = inferOwnerFromInvocation(inv);
            if (inferred != null) return inferred;
        }
        if (useSite instanceof CtConstructorCall<?> cc) {
            String inferred = inferOwnerFromConstructorCall(cc);
            if (inferred != null) return inferred;
        }
        if (useSite instanceof CtExecutableReferenceExpression ere) {
            String inferred = inferOwnerFromExecutableReferenceExpression(ere);
            if (inferred != null) return inferred;
        }
        return "?";
    }

    public static boolean isUsableQualifiedName(String q) {
        return q != null && !q.isBlank() && !"?".equals(q);
    }

    public static String inferOwnerFromInvocation(CtInvocation<?> inv) {
        CtExpression<?> target = inv.getTarget();
        if (target instanceof CtTypeAccess<?> ta) {
            try {
                if (ta.getAccessedType() != null) {
                    String q = ta.getAccessedType().getQualifiedName();
                    if (isUsableQualifiedName(q)) return q;
                }
            } catch (Exception ignored) { }
        }
        if (target != null) {
            try {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    if (isUsableQualifiedName(q)) return q;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static String inferOwnerFromConstructorCall(CtConstructorCall<?> cc) {
        try {
            if (cc.getType() != null) {
                String q = cc.getType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static String inferOwnerFromExecutableReferenceExpression(
            CtExecutableReferenceExpression<?, ?> ere) {
        try {
            CtExpression<?> target = ere.getTarget();
            if (target instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
                String q = ta.getAccessedType().getQualifiedName();
                if (isUsableQualifiedName(q)) return q;
            }
            if (target != null) {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    if (isUsableQualifiedName(q)) return q;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------------
    // Position / path helpers
    // ------------------------------------------------------------------

    public static String sourceFile(CtElement el) {
        try {
            var pos = el.getPosition();
            if (pos.isValidPosition()) {
                if (pos.getFile() != null) return pos.getFile().getPath().replace("\\", "/");
                var cu = pos.getCompilationUnit();
                if (cu != null) {
                    String f = cu.getFile() != null ? cu.getFile().getPath()
                            : (cu.getMainType() != null
                               ? cu.getMainType().getQualifiedName().replace('.', '/') + ".java"
                               : "");
                    if (!f.isEmpty()) return f;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Path for graph storage and {@link #extractSource} lookup: repo-relative with forward slashes when possible.
     */
    public static String graphFilePath(CtElement el, Path projectRoot, Set<String> repoRelativeSourcePaths) {
        String spoon = sourceFile(el);
        if (spoon == null || spoon.isBlank()) return spoon;
        if (projectRoot == null) return spoon.replace('\\', '/');

        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            try { root = root.toRealPath(); } catch (Exception ignored) { }
            Path abs = Path.of(spoon).toAbsolutePath().normalize();
            try { abs = abs.toRealPath(); } catch (Exception ignored) { }
            if (abs.startsWith(root)) {
                return root.relativize(abs).toString().replace('\\', '/');
            }
        } catch (Exception ignored) { }

        String norm = spoon.replace('\\', '/');
        if (repoRelativeSourcePaths != null && !repoRelativeSourcePaths.isEmpty()) {
            String best = null;
            int bestLen = -1;
            for (String rel : repoRelativeSourcePaths) {
                if (rel == null || rel.isBlank()) continue;
                String r = rel.replace('\\', '/');
                if (norm.endsWith(r) && r.length() > bestLen) { best = r; bestLen = r.length(); }
            }
            if (best != null) return best;
        }

        int cut = indexOfStandardSourceRoot(norm);
        if (cut >= 0) return norm.substring(cut).replace('\\', '/');
        return spoon;
    }

    private static int indexOfStandardSourceRoot(String normalizedForwardSlashes) {
        String lower = normalizedForwardSlashes.toLowerCase(Locale.ROOT);
        int m = lower.indexOf("src/main/java/");
        int t = lower.indexOf("src/test/java/");
        if (m < 0) return t;
        if (t < 0) return m;
        return Math.min(m, t);
    }

    public static int[] lines(CtElement el) {
        try { var p = el.getPosition(); if (p.isValidPosition()) return new int[]{ p.getLine(), p.getEndLine() }; }
        catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    /**
     * Fallback variant — uses CU source when no {@code fileLines} are available.
     */
    public static int posLine(CtElement el) {
        try {
            var p = el.getPosition();
            if (!p.isValidPosition()) return -1;
            CompilationUnit cu = p.getCompilationUnit();
            String src = cu != null ? cu.getOriginalSourceCode() : null;
            if (src != null && !src.isEmpty()) return lineNumberAtOffset(src, p.getSourceStart());
            return p.getLine();
        } catch (Exception e) { return -1; }
    }

    /**
     * 1-based line number of the character at {@code offset} (0-based) in {@code src}.
     */
    public static int lineNumberAtOffset(String src, int offset) {
        if (src == null || src.isEmpty()) return 1;
        int n = Math.min(Math.max(offset, 0), src.length());
        int line = 1;
        for (int i = 0; i < n; i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ------------------------------------------------------------------
    // Graph path normalisation
    // ------------------------------------------------------------------

    /**
     * Translate a path from a GitLab diff into the graph-relative path.
     */
    public static String normalizeFilePath(String diffPath, ProjectGraph graph) {
        if (diffPath == null || diffPath.isBlank()) return diffPath;
        String norm = diffPath.replace('\\', '/');
        for (String known : graph.allFilePaths()) {
            String knownNorm = known.replace('\\', '/');
            if (norm.equals(knownNorm))         return known;
            if (norm.endsWith("/" + knownNorm)) return known;
            if (knownNorm.endsWith("/" + norm)) return known;
        }
        String[] parts = norm.split("/");
        for (int i = 1; i < parts.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, i, parts.length));
            if (graph.byFile.containsKey(candidate)) return candidate;
        }
        return diffPath;
    }

    /**
     * Resolves {@code sourceLines} the same way as {@link #extractSource}, then extracts a declaration.
     * Returns {@code ""} when {@code fileLines} cannot be resolved.
     */
    public static String declarationOf(Map<String, String[]> sourceLines, String filePath,
                                       Path projectRoot, SourcePosition pos) {
        String[] lines = findLines(sourceLines, filePath, projectRoot);
        if (lines == null) return "";
        return declarationOf(lines, pos);
    }

    /**
     * Extracts the declaration (signature/header without method body) from {@code sourceLines}.
     */
    public static String declarationOf(String[] sourceLines, SourcePosition pos) {
        if (sourceLines == null || sourceLines.length == 0 || pos == null || !pos.isValidPosition()) {
            return "";
        }
        String full = String.join("\n", sourceLines);

        if (pos instanceof BodyHolderSourcePosition bodyPos) {
            int start = bodyPos.getDeclarationStart();
            int bodyStart = bodyPos.getBodyStart();
            if (bodyStart > start) {
                return trimDeclaration(safeSubstring(full, start, bodyStart));
            }
        }
        if (pos instanceof CompoundSourcePosition declPos) {
            int start = declPos.getDeclarationStart();
            int endInclusive = declPos.getDeclarationEnd();
            if (endInclusive >= start) {
                return trimDeclaration(safeSubstring(full, start, endInclusive + 1));
            }
        }
        int line = pos.getLine();
        int endLine = pos.getEndLine();
        if (line < 1) return "";
        int from = line - 1;
        int to = (endLine >= line) ? endLine - 1 : from;
        if (from >= sourceLines.length) return "";
        to = Math.min(to, sourceLines.length - 1);
        if (from > to) return trimDeclaration(sourceLines[from]);
        return trimDeclaration(IntStream.rangeClosed(from, to)
                .mapToObj(i -> sourceLines[i])
                .collect(Collectors.joining("\n")));
    }

    /**
     * Removes line and block comments (including Javadoc) from a Java source fragment,
     * without touching text inside string/char literals or text blocks.
     */
    public static String stripJavaCommentsAndJavadoc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = s.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < n && s.charAt(i) != '\r' && s.charAt(i) != '\n') i++;
                    if (i < n && s.charAt(i) == '\r') i++;
                    if (i < n && s.charAt(i) == '\n') i++;
                    out.append(' ');
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    if (i + 1 < n) i += 2; else i = n;
                    out.append(' ');
                    continue;
                }
            }
            if (c == '"') {
                if (i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                    out.append("\"\"\""); i += 3;
                    while (i < n) {
                        if (i + 2 < n && s.charAt(i) == '"' && s.charAt(i+1) == '"' && s.charAt(i+2) == '"') {
                            out.append("\"\"\""); i += 3; break;
                        }
                        out.append(s.charAt(i++));
                    }
                    continue;
                }
                out.append(c); i++;
                while (i < n) {
                    char c2 = s.charAt(i); out.append(c2);
                    if (c2 == '\\' && i + 1 < n) { out.append(s.charAt(i + 1)); i += 2; continue; }
                    if (c2 == '"') { i++; break; }
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                out.append(c); i++;
                while (i < n) {
                    char c2 = s.charAt(i); out.append(c2);
                    if (c2 == '\\' && i + 1 < n) { out.append(s.charAt(i + 1)); i += 2; continue; }
                    if (c2 == '\'') { i++; break; }
                    i++;
                }
                continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    private static String trimDeclaration(String s) {
        if (s == null) return "";
        return stripJavaCommentsAndJavadoc(s.replace("\r", "")).replaceAll("\\s+", " ").trim();
    }

    private static String safeSubstring(String full, int start, int endExclusive) {
        if (full == null || full.isEmpty()) return "";
        if (start < 0) start = 0;
        if (endExclusive > full.length()) endExclusive = full.length();
        if (start >= endExclusive) return "";
        return full.substring(start, endExclusive);
    }
}
