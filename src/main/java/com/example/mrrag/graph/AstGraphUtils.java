package com.example.mrrag.graph;

import com.example.mrrag.graph.model.ProjectGraph;
import spoon.reflect.code.*;
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

    public static String varId(CtVariable<?> v) {
        if (!v.getPosition().isValidPosition()) return null;
        String file = v.getPosition().getFile() != null ? v.getPosition().getFile().getName() : "?";
        return "var@" + file + ":" + v.getPosition().getLine() + ":" + v.getSimpleName();
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
            } catch (Exception ignored) {
            }
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
        } catch (Exception ignored) {
        }
        try {
            CtExecutable<?> decl = ref.getDeclaration();
            if (decl instanceof CtTypeMember tm && tm.getDeclaringType() != null) {
                String q = tm.getDeclaringType().getQualifiedName();
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
        if (target instanceof CtTypeAccess<?> ta) {
            try {
                if (ta.getAccessedType() != null) {
                    String q = ta.getAccessedType().getQualifiedName();
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
                if (isUsableQualifiedName(q)) return q;
            }
            if (target != null) {
                CtTypeReference<?> t = target.getType();
                if (t != null) {
                    String q = t.getQualifiedName();
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
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Path for graph storage and {@link #extractSource} lookup: repo-relative with forward slashes when possible.
     */
    public static String graphFilePath(CtElement el, Path projectRoot, Set<String> repoRelativeSourcePaths) {
        String spoon = sourceFile(el);
        if (spoon.isBlank()) return spoon;
        if (projectRoot == null) return spoon.replace('\\', '/');

        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            try {
                root = root.toRealPath();
            } catch (Exception ignored) {
            }
            Path abs = Path.of(spoon).toAbsolutePath().normalize();
            try {
                abs = abs.toRealPath();
            } catch (Exception ignored) {
            }
            if (abs.startsWith(root)) {
                return root.relativize(abs).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }

        String norm = spoon.replace('\\', '/');
        if (repoRelativeSourcePaths != null && !repoRelativeSourcePaths.isEmpty()) {
            String best = null;
            int bestLen = -1;
            for (String rel : repoRelativeSourcePaths) {
                if (rel == null || rel.isBlank()) continue;
                String r = rel.replace('\\', '/');
                if (norm.endsWith(r) && r.length() > bestLen) {
                    best = r;
                    bestLen = r.length();
                }
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

    /**
     * Returns line range [{@code startLine}, {@code endLine}] (1-based, inclusive)
     * for the *declaration* part of an element (signature/header without the method body).
     * <p>
     * Strategy (in priority order):
     * <ol>
     *   <li>{@link BodyHolderSourcePosition} — uses {@code declarationStart} .. {@code bodyStart-1}</li>
     *   <li>{@link CompoundSourcePosition}   — uses {@code declarationStart} .. {@code declarationEnd}</li>
     * </ol>
     *
     * @param el          the element whose declaration lines are needed
     * @param sourceLines repo-relative path -> source lines map (used for offset->line conversion)
     * @return int[2]: [startLine, endLine], or [-1, -1] when lines cannot be resolved
     */
    public static int[] declarationLines(CtElement el, Map<String, String[]> sourceLines) {
        if (el == null) return new int[]{-1, -1};
        try {
            SourcePosition p = el.getPosition();
            if (p == null || !p.isValidPosition()) {
                return lines(el, sourceLines);
            }

            String filePath = sourceFile(el);
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
                        // bodyStart points to '{' — declaration ends on the line just before it,
                        // or on the same line if the brace is on the same line as the last param.
                        int endLine = lineNumberAtOffset(full, bodyStart - 1);
                        if (startLine > 0 && endLine >= startLine) {
                            return new int[]{startLine, endLine};
                        }
                    }
                    // No source text — fall back to Spoon line numbers
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

            // For elements without a body (fields, imports, annotations, etc.)
            // the full position IS the declaration.
            return lines(el, sourceLines);

        } catch (Exception ignored) {
            return new int[]{-1, -1};
        }
    }

    /**
     * Best-effort line range for an element.
     * First uses Spoon position line numbers; if they are unavailable or invalid,
     * falls back to source offset -> line conversion using sourceLines.
     * <p>
     * Returns {-1, -1} when neither direct position nor source-backed fallback works.
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

            String filePath = sourceFile(el);
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
        int to   = declLines[1];
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
     * Это происходит в no-classpath режиме для внешних зависимостей.
     * <p>
     * Возвращает {@code null}, если строку не удалось извлечь или она не является импортом.
     *
     * @param sourceLines карта путь -> строки файла
     * @param filePath    граф-относительный путь к файлу
     * @param lineNumber  1-based номер строки (из {@code imp.getPosition().getLine()})
     * @return полное имя из импорта (например {@code "java.util.List"}), либо {@code null}
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
     * Используется как fallback когда {@link spoon.reflect.declaration.CtImport#getImportKind()}
     * ненадёжен из-за неразрезолвленной ссылки.
     *
     * @param sourceLines карта путь -> строки файла
     * @param filePath    граф-относительный путь к файлу
     * @param lineNumber  1-based номер строки
     * @return {@code true} если строка содержит {@code import static}
     */
    public static boolean isStaticImportBySource(Map<String, String[]> sourceLines, String filePath, int lineNumber) {
        if (lineNumber < 1 || filePath == null || filePath.isBlank()) return false;
        String[] lines = findLines(sourceLines, filePath);
        if (lines == null || lineNumber > lines.length) return false;
        return lines[lineNumber - 1].contains("import static");
    }

}
