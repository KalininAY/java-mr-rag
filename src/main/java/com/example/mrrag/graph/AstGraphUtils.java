package com.example.mrrag.graph;

import com.example.mrrag.graph.model.ProjectGraph;
import spoon.reflect.code.*;
import spoon.reflect.cu.CtCompilationUnit;
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
 * {@link GraphBuilder} to keep the main service class focused on
 * orchestration rather than low-level details.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Spoon element ID computation (qualified names, executable IDs, etc.)</li>
 *   <li>Source snippet extraction from in-memory line arrays</li>
 *   <li>Position/path helpers (relPath, lines, posLine, sourceFile)</li>
 *   <li>Owner inference for invocations and constructor calls</li>
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
     */
    public static String extractSource(Map<String, String[]> sourceLines,
                                       String filePath, int startLine, int endLine) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = findLines(sourceLines, filePath);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to = Math.min(lines.length, endLine);
                if (from < to) return String.join("\n", Arrays.copyOfRange(lines, from, to));
            }
        }
        return ""; // нет исходного кода
    }

    /**
     * Resolves {@code sourceLines} lookup when keys are repo-relative (e.g. {@code src/main/java/Foo.java})
     * but {@code filePath} is absolute (Spoon positions), or when separator styles differ.
     */
    static String[] findLines(Map<String, String[]> sourceLines, String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        String[] lines = sourceLines.get(filePath);
        if (lines != null) return lines;

        try {
            String n = Path.of(filePath).normalize().toString();
            lines = sourceLines.get(n);
            if (lines != null) return lines;
        } catch (Exception ignored) {
        }

        lines = sourceLines.get(filePath.replace('\\', '/'));
        if (lines != null) return lines;

        lines = sourceLines.get(filePath.replace('/', '\\'));
        return lines;
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

    /**
     * Builds a stable, line-number-independent ID for a local variable or parameter.
     *
     * <p>Strategy (priority order):
     * <ol>
     *   <li><b>Method/constructor parameter</b> — {@code execId#param:<name>}</li>
     *   <li><b>Lambda parameter or variable inside a lambda</b> — {@code execId#λ<lambdaLine>:<name>}
     *       where {@code lambdaLine} is the source line of the enclosing lambda expression.
     *       This line shifts together with the whole method when unrelated lines are added/removed
     *       above, so two versions of the same lambda still produce the same ID.</li>
     *   <li><b>Local variable inside a method/constructor</b> — {@code execId#<name>}</li>
     *   <li><b>Fallback</b> (no enclosing executable found) — {@code var@<fileName>:<name>}
     *       (no line number).</li>
     * </ol>
     *
     * @param v the variable element; must not be {@code null}
     * @return a stable string ID for use as a graph node key
     */
    public static String varId(CtVariable<?> v) {
        if (v == null) return "unresolved";

        // 1. Parameter of method/constructor
        if (v instanceof CtParameter<?>) {
            CtExecutable<?> exec = v.getParent(CtExecutable.class);
            if (exec instanceof CtTypeMember tm) {
                String execId = typeMemberExecId(tm);
                if (execId != null) return "var@" + execId + "#param:" + v.getSimpleName();
            }
        }

        CtLambda<?> lambda = v.getParent(CtLambda.class);
        CtTypeMember enclosing = v.getParent(CtMethod.class);
        if (enclosing == null) enclosing = v.getParent(CtConstructor.class);
        String execId = enclosing != null ? typeMemberExecId(enclosing) : null;

        // 2. Variable inside lambda
        if (lambda != null && execId != null) return "var@" + execId + "#\u03bb" + v.getSimpleName();
        // 3. Ordinary local variable inside method/constructor
        if (execId != null) return "var@" + execId + "#" + v.getSimpleName();

        // 4. Fallback (e.g. initializer block)
        String fileName = "?";
        try {
            SourcePosition pos = v.getPosition();
            if (pos != null && pos.isValidPosition() && pos.getFile() != null)
                fileName = pos.getFile().getName();
        } catch (Exception ignored) {
        }
        return "var@" + fileName + ":" + v.getSimpleName();
    }

    public static String varRefId(CtVariableReference<?> ref) {
        if (ref == null) return "var@?";
        try {
            CtVariable<?> d = ref.getDeclaration();
            if (d != null) return varId(d);
        } catch (Exception ignored) {
        }
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
            String sig = buildSignature(ref, useSite);
            if (ref.isConstructor()) {
                return constructorExecutableId(owner, sig);
            }
            return owner + "#" + sig;
        } catch (Exception e) {
            return "unresolved:" + ref.getSimpleName();
        }
    }

    /**
     * Builds a method/constructor signature string.
     * If Spoon already resolved parameters on the reference, delegates to
     * {@link CtExecutableReference#getSignature()}. Otherwise tries to infer
     * parameter types from the actual call-site arguments (works in
     * no-classpath mode for literals and variables whose types are known).
     * <p>
     * Uses comma without space as separator to match Spoon's own
     * {@link CtExecutableReference#getSignature()} format.
     *
     * @param ref     the executable reference (may have empty parameter list)
     * @param useSite the call-site element ({@link CtInvocation} or {@link CtConstructorCall})
     * @return a signature string such as {@code "foo(java.lang.String,int)"}
     */
    public static String buildSignature(CtExecutableReference<?> ref, CtElement useSite) {
        // If Spoon already resolved parameters — trust it
        try {
            if (ref.getParameters() != null && !ref.getParameters().isEmpty()) {
                return ref.getSignature();
            }
        } catch (Exception ignored) {
        }

        // Collect call-site arguments
        List<CtExpression<?>> args = null;
        if (useSite instanceof CtInvocation<?> inv) {
            args = inv.getArguments();
        } else if (useSite instanceof CtConstructorCall<?> cc) {
            args = cc.getArguments();
        }

        if (args == null || args.isEmpty()) {
            return ref.getSignature();
        }

        List<String> paramTypes = new ArrayList<>();
        for (CtExpression<?> arg : args) {
            String typeName = inferArgType(arg);
            if (typeName == null) {
                // Cannot infer at least one type — fall back to Spoon signature
                return ref.getSignature();
            }
            paramTypes.add(typeName);
        }

        // No space after comma — matches Spoon's getSignature() format
        return ref.getSimpleName() + "(" + String.join(",", paramTypes) + ")";
    }

    /**
     * Best-effort inference of the qualified type name of a call-site argument.
     * Returns {@code null} when the type cannot be determined.
     * <p>
     * Handles:
     * <ul>
     *   <li>Spoon-resolved types (most cases with classpath)</li>
     *   <li>String/numeric/boolean literals</li>
     *   <li>String concatenation via {@code +} operator (recursively)</li>
     * </ul>
     */
    private static String inferArgType(CtExpression<?> arg) {
        // 1. Spoon already knows the type
        try {
            CtTypeReference<?> t = arg.getType();
            if (t != null) {
                String q = t.getQualifiedName();
                if (isUsableQualifiedName(q) && !"?".equals(q)) return q;
            }
        } catch (Exception ignored) {
        }

        // 2. String literal
        if (arg instanceof CtLiteral<?> lit && lit.getValue() instanceof String) {
            return "java.lang.String";
        }

        // 3. Numeric / boolean literals
        if (arg instanceof CtLiteral<?> lit) {
            Object v = lit.getValue();
            if (v instanceof Integer) return "int";
            if (v instanceof Long)    return "long";
            if (v instanceof Double)  return "double";
            if (v instanceof Float)   return "float";
            if (v instanceof Boolean) return "boolean";
        }

        // 4. String concatenation: "..." + "..." + ...
        //    If either operand resolves to String, the whole expression is a String.
        if (arg instanceof CtBinaryOperator<?> bin
                && bin.getKind() == BinaryOperatorKind.PLUS) {
            String left  = inferArgType(bin.getLeftHandOperand());
            String right = inferArgType(bin.getRightHandOperand());
            if ("java.lang.String".equals(left) || "java.lang.String".equals(right)) {
                return "java.lang.String";
            }
        }

        return null;
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
            } catch (Exception ignored) {
            }
        }
        return base;
    }

    // ------------------------------------------------------------------
    // Owner inference
    // ------------------------------------------------------------------

    private static String refineOwnerUsingImports(String q, CtElement ctx) {
        if (ctx == null || q == null || q.isBlank()) return null;

        CtCompilationUnit cu = null;
        try {
            SourcePosition pos = ctx.getPosition();
            if (pos != null && pos.isValidPosition()) {
                cu = pos.getCompilationUnit();
            }
        } catch (Exception ignored) {}
        if (cu == null) return null;

        String simple = q.contains(".") ? q.substring(q.lastIndexOf('.') + 1) : q;

        String candidate = null;
        try {
            for (CtImport imp : cu.getImports()) {
                var ref = imp.getReference();
                if (ref == null) continue;
                String fqn = ref.toString();
                if (fqn.endsWith("." + simple)) {
                    if (candidate == null) {
                        candidate = fqn;
                    } else if (!candidate.equals(fqn)) {
                        return null; // ambiguous
                    }
                }
            }
        } catch (Exception ignored) {}

        if (candidate == null || candidate.equals(q)) return null;
        return candidate;
    }

    public static String qualifiedExecutableOwner(CtExecutableReference<?> ref, CtElement useSite) {
        try {
            if (ref.getDeclaringType() != null) {
                String q = ref.getDeclaringType().getQualifiedName();
                String fixed = refineOwnerUsingImports(q, useSite);
                if (fixed != null) q = fixed;
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) {
        }
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl instanceof CtTypeMember tm && tm.getDeclaringType() != null) {
                String q = tm.getDeclaringType().getQualifiedName();
                String fixed = refineOwnerUsingImports(q, decl);
                if (fixed != null) q = fixed;
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) {
        }

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

        // null (implicit this), explicit this, or Interface.super calls
        if (target == null || target instanceof CtThisAccess || target instanceof CtSuperAccess) {

            // For Interface.super — try the type of the super-access first
            if (target instanceof CtSuperAccess<?> sa) {
                try {
                    CtTypeReference<?> dt = sa.getType();
                    if (dt != null) {
                        String q = dt.getQualifiedName();
                        String fixed = refineOwnerUsingImports(q, inv);
                        if (fixed != null) q = fixed;
                        if (isUsableQualifiedName(q)) return q;
                    }
                } catch (Exception ignored) {
                }
            }

            // Fallback — enclosing declaring type (works for both this and super)
            try {
                CtType<?> enclosing = inv.getParent(CtType.class);
                if (enclosing != null) {
                    String q = enclosing.getQualifiedName();
                    if (isUsableQualifiedName(q)) return q;
                }
            } catch (Exception ignored) {
            }
        }

        if (target instanceof CtTypeAccess<?> ta) {
            try {
                if (ta.getAccessedType() != null) {
                    String q = ta.getAccessedType().getQualifiedName();
                    String fixed = refineOwnerUsingImports(q, inv);
                    if (fixed != null) q = fixed;
                    if (isUsableQualifiedName(q)) return q;
                }
            } catch (Exception ignored) {
            }
        }
        if (target != null) {
            try {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    String fixed = refineOwnerUsingImports(q, inv);
                    if (fixed != null) q = fixed;
                    if (isUsableQualifiedName(q)) return q;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static String inferOwnerFromConstructorCall(CtConstructorCall<?> cc) {
        try {
            if (cc.getType() != null) {
                String q = cc.getType().getQualifiedName();
                String fixed = refineOwnerUsingImports(q, cc);
                if (fixed != null) q = fixed;
                if (isUsableQualifiedName(q)) return q;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String inferOwnerFromExecutableReferenceExpression(
            CtExecutableReferenceExpression<?, ?> ere) {
        try {
            CtExpression<?> target = ere.getTarget();
            if (target instanceof CtTypeAccess<?> ta && ta.getAccessedType() != null) {
                String q = ta.getAccessedType().getQualifiedName();
                String fixed = refineOwnerUsingImports(q, ere);
                if (fixed != null) q = fixed;
                if (isUsableQualifiedName(q)) return q;
            }
            if (target != null) {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
                    String fixed = refineOwnerUsingImports(q, ere);
                    if (fixed != null) q = fixed;
                    if (isUsableQualifiedName(q)) return q;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Position / path helpers
    // ------------------------------------------------------------------

    /**
     * Repo-relative path из sourceLines по суффиксу qualified name владельца.
     * Для VirtualFile pos.getFile() == null — ищем по ключам sourceLines.
     * Fallback: Spoon CU + стандартные src roots.
     */
    public static String graphFilePath(CtElement el, Path projectRoot, Set<String> repoPaths) {
        // 1. Ищем владельца-тип для построения суффикса
        try {
            CtType<?> owner = el instanceof CtType<?> t ? t
                    : el instanceof CtTypeMember m ? m.getDeclaringType()
                    : el.getParent(CtType.class);
            if (owner != null) {
                CtType<?> topLevel = owner;
                while (topLevel.getDeclaringType() != null) {
                    topLevel = topLevel.getDeclaringType();
                }

                String qualifiedName = topLevel.getQualifiedName();
                String suffix = qualifiedName.replace('.', '/') + ".java";

                String found = repoPaths.stream()
                        .filter(p -> p.replace('\\', '/').endsWith(suffix))
                        .findFirst().orElse(null);
                if (found != null) return found;
            }
        } catch (Exception ignored) {
        }

        // 2. Fallback: Spoon позиция + relativize / стандартные src roots
        try {
            var pos = el.getPosition();
            if (pos != null && pos.isValidPosition()) {
                String spoon = pos.getFile() != null
                        ? pos.getFile().getPath()
                        : pos.getCompilationUnit() != null && pos.getCompilationUnit().getMainType() != null
                        ? pos.getCompilationUnit().getMainType().getQualifiedName().replace('.', '/') + ".java"
                        : "";
                if (spoon.isBlank())
                    return "";
                String norm = spoon.replace('\\', '/');
                if (projectRoot != null) {
                    Path root = projectRoot.toAbsolutePath().normalize();
                    Path abs = Path.of(spoon).toAbsolutePath().normalize();
                    if (abs.startsWith(root)) return root.relativize(abs).toString().replace('\\', '/');
                }
                int cut = indexOfStandardSourceRoot(norm);
                return cut >= 0 ? norm.substring(cut) : norm;
            }
        } catch (Exception ignored) {
        }

        return "";
    }

    private static int indexOfStandardSourceRoot(String normalizedForwardSlashes) {
        String lower = normalizedForwardSlashes.toLowerCase(Locale.ROOT);
        int m = lower.indexOf("src/main/java/");
        int t = lower.indexOf("src/test/java/");
        if (m < 0) return t;
        if (t < 0) return m;
        return Math.min(m, t);
    }

    /**
     * Returns line range [{@code startLine}, {@code endLine}] (1-based, inclusive)
     * for the *declaration* part of an element (signature/header without the method body).
     */
    public static int[] declarationLines(CtElement el, Map<String, String[]> sourceLines) {
        if (el == null) return new int[]{-1, -1};
        try {
            SourcePosition p = el.getPosition();
            if (p == null || !p.isValidPosition()) {
                return lines(el, sourceLines);
            }

            String filePath = graphFilePath(el, null, sourceLines.keySet());
            String[] fileLines = findLines(sourceLines, filePath);
            String full = fileLines != null && fileLines.length > 0
                    ? String.join("\n", fileLines)
                    : null;

            if (p instanceof BodyHolderSourcePosition bodyPos) {
                int declStart = bodyPos.getDeclarationStart();
                int bodyStart = bodyPos.getBodyStart();
                if (declStart >= 0 && bodyStart > declStart) {
                    if (full != null) {
                        int startLine = lineNumberAtOffset(full, declStart);
                        int endLine = lineNumberAtOffset(full, bodyStart - 1);
                        if (startLine > 0 && endLine >= startLine) {
                            return new int[]{startLine, endLine};
                        }
                    }
                    return new int[]{p.getLine(), p.getEndLine()};
                }
            }

            if (p instanceof CompoundSourcePosition declPos) {
                int declStart = declPos.getDeclarationStart();
                int declEnd = declPos.getDeclarationEnd();
                if (declStart >= 0 && declEnd >= declStart && full != null) {
                    int startLine = lineNumberAtOffset(full, declStart);
                    int endLine = lineNumberAtOffset(full, declEnd);
                    if (startLine > 0 && endLine >= startLine) {
                        return new int[]{startLine, endLine};
                    }
                }
            }

            return lines(el, sourceLines);

        } catch (Exception ignored) {
            return new int[]{-1, -1};
        }
    }

    /**
     * Best-effort line range for an element.
     */
    public static int[] lines(CtElement el, Map<String, String[]> sourceLines) {
        if (el == null) return new int[]{-1, -1};

        try {
            SourcePosition p = el.getPosition();
            if (p == null) return new int[]{-1, -1};

            int startLine = p.isValidPosition() ? p.getLine() : -1;
            int endLine = p.isValidPosition() ? p.getEndLine() : -1;

            if (startLine > 0 && endLine >= startLine) {
                return new int[]{startLine, endLine};
            }

            String filePath = graphFilePath(el, null, sourceLines.keySet());
            String[] fileLines = findLines(sourceLines, filePath);
            if (fileLines == null || fileLines.length == 0) {
                return new int[]{startLine, endLine};
            }

            int sourceStart = p.getSourceStart();
            int sourceEnd = p.getSourceEnd();

            if (sourceStart < 0) {
                return new int[]{startLine, endLine};
            }

            String full = String.join("\n", fileLines);
            int computedStart = lineNumberAtOffset(full, sourceStart);
            int computedEnd = sourceEnd >= sourceStart
                    ? lineNumberAtOffset(full, sourceEnd)
                    : computedStart;

            if (computedStart > 0 && computedEnd >= computedStart) {
                return new int[]{computedStart, computedEnd};
            }

            return new int[]{startLine, endLine};
        } catch (Exception ignored) {
            return new int[]{-1, -1};
        }
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
            if (norm.equals(knownNorm)) return known;
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
    public static String declarationOf(Map<String, String[]> sourceLines, String filePath, CtElement el) {
        String[] lines = findLines(sourceLines, filePath);
        if (lines == null) return "";
        int[] declLines = declarationLines(el, sourceLines);
        int from = declLines[0];
        int to = declLines[1];
        if (from > 0 && to >= from && to <= lines.length) {
            return IntStream.rangeClosed(from - 1, to - 1)
                    .mapToObj(i -> lines[i])
                    .collect(Collectors.joining("\n"));
        } else return "";
    }


    // ------------------------------------------------------------------
    // Import parsing helpers
    // ------------------------------------------------------------------

    /**
     * Парсит строку импорта из исходников когда Spoon не может разрезолвить
     * ссылку ({@link spoon.reflect.declaration.CtImport#getReference()} == null).
     */
    public static String parseImportRefFromSource(Map<String, String[]> sourceLines, String filePath, int lineNumber) {
        if (lineNumber < 1 || filePath == null || filePath.isBlank()) return null;
        String[] lines = findLines(sourceLines, filePath);
        if (lines == null || lineNumber > lines.length) return null;

        String raw = lines[lineNumber - 1].trim();
        if (!raw.startsWith("import ")) return null;

        String ref = raw
                .replaceFirst("^import\\s+static\\s+", "")
                .replaceFirst("^import\\s+", "")
                .replace(";", "")
                .trim();
        return ref.isBlank() ? null : ref;
    }

    /**
     * Определяет является ли импорт статическим по исходной строке.
     */
    public static boolean isStaticImportBySource(Map<String, String[]> sourceLines, String filePath, int lineNumber) {
        if (lineNumber < 1 || filePath == null || filePath.isBlank()) return false;
        String[] lines = findLines(sourceLines, filePath);
        if (lines == null || lineNumber > lines.length) return false;
        return lines[lineNumber - 1].contains("import static");
    }

}
