package com.example.mrrag.graph;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/**
 * Utility class containing stateless helper methods extracted from
 * to keep the main
 * service class focused on orchestration rather than low-level details.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Spoon element ID computation (qualified names, executable IDs, etc.)</li>
 *   <li>Source snippet extraction from in-memory line arrays</li>
 *   <li>Position/path helpers (relPath, lines, posLine, sourceFile)</li>
 *   <li>Owner inference for invocations and constructor calls</li>
 *   <li>Body hash computation for semantic diff ({@link #bodyHash(GraphNode)})</li>
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

    public static String extractSource(Map<String, String[]> sourceLines,
                                       String filePath, int startLine, int endLine,
                                       CtElement el) {
        if (startLine > 0 && endLine >= startLine) {
            String[] lines = sourceLines.get(filePath);
            if (lines != null) {
                int from = Math.max(0, startLine - 1);
                int to   = Math.min(lines.length, endLine);
                if (from < to) return String.join("\n", Arrays.copyOfRange(lines, from, to));
            }
        }
        return snippet(el);
    }

    public static String snippet(CtElement el) {
        try { String s = el.toString(); return s != null ? s : ""; } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------
    // Body hash (for semantic diff comparison)
    // ------------------------------------------------------------------

    /**
     * Computes a stable SHA-256 hex hash of the node's body, suitable for
     * detecting whether a method/class body has actually changed between two
     * versions of the graph (used by {@code SemanticDiffFilter}).
     *
     * <p>The hash input is built as follows:
     * <ol>
     *   <li>If {@link GraphNode#sourceSnippet()} is non-blank, it is normalised
     *       (whitespace collapsed, leading/trailing stripped) and hashed.</li>
     *   <li>Otherwise {@link GraphNode#declarationSnippet()} is used in the
     *       same way — useful for interface methods or abstract declarations
     *       that have no body.</li>
     *   <li>If both fields are blank, the node {@link GraphNode#id()} is hashed
     *       so that the result is still deterministic and unique per node.</li>
     * </ol>
     *
     * @param node the graph node whose body should be hashed; must not be {@code null}
     * @return lowercase hex SHA-256 string (64 characters), never {@code null}
     */
    public static String bodyHash(GraphNode node) {
        String body = node.sourceSnippet();
        if (body == null || body.isBlank()) {
            body = node.declarationSnippet();
        }
        if (body == null || body.isBlank()) {
            body = node.id();
        }
        // Normalise: collapse all whitespace runs to a single space so that
        // purely cosmetic re-formatting does not produce a different hash.
        String normalised = body.strip().replaceAll("\\s+", " ");
        return sha256Hex(normalised);
    }

    /**
     * Convenience overload — returns {@code null} when {@code node} is {@code null}.
     */
    public static String bodyHash(GraphNode node, boolean nullSafe) {
        if (nullSafe && node == null) return null;
        return bodyHash(node);
    }

    /**
     * Returns {@code true} when the two nodes have identical body hashes,
     * meaning their source bodies are semantically equivalent (modulo
     * whitespace normalisation).
     *
     * @param a first node (may be {@code null})
     * @param b second node (may be {@code null})
     * @return {@code true} iff both nodes are non-null and their body hashes match
     */
    public static boolean sameBody(GraphNode a, GraphNode b) {
        if (a == null || b == null) return false;
        return bodyHash(a).equals(bodyHash(b));
    }

    // ------------------------------------------------------------------
    // Internal SHA-256 helper
    // ------------------------------------------------------------------

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — should never happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
                if (pos.getFile() != null) return pos.getFile().getAbsolutePath();
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

    public static String relPath(String root, String abs) {
        if (abs.isEmpty()) return "unknown";
        if (root.isEmpty()) return abs;
        return abs.startsWith(root)
                ? abs.substring(root.length()).replaceFirst("^[/\\\\]", "") : abs;
    }

    public static int[] lines(CtElement el) {
        try { var p = el.getPosition(); if (p.isValidPosition()) return new int[]{ p.getLine(), p.getEndLine() }; }
        catch (Exception ignored) {}
        return new int[]{ -1, -1 };
    }

    public static int posLine(CtElement el) {
        try { var p = el.getPosition(); return p.isValidPosition() ? p.getLine() : -1; }
        catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------
    // Graph path normalisation (moved from AstGraphService)
    // ------------------------------------------------------------------

    /**
     * Translate a path from a GitLab diff into the graph-relative path.
     */
    public static String normalizeFilePath(String diffPath, ProjectGraph graph) {
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
        return diffPath;
    }
}
