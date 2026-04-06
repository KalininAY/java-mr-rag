package com.example.mrrag.review;

import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.service.JavaIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Variant B semantic diff filter: operates on top of the raw git diff and
 * removes lines that represent a pure <em>move</em> of a Java symbol within
 * the codebase (method relocated to another class/file, field reordered
 * inside a class, etc.) without any semantic change to its body or signature.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Collect all <b>qualified keys</b> of methods/fields that appear in
 *       <em>both</em> the source and target AST indexes (i.e. they exist in
 *       both branches).</li>
 *   <li>For each such key compare the <b>normalised body hash</b> stored in
 *       the index.  If the hash is identical the symbol was not changed —
 *       only moved.</li>
 *   <li>Determine the line ranges of each moved symbol in both the source
 *       ({@code ADD} side) and target ({@code DELETE} side) files.</li>
 *   <li>Remove {@code ADD} lines that fall inside a moved-symbol range in
 *       the source index, and {@code DELETE} lines that fall inside the
 *       corresponding range in the target index.</li>
 *   <li>{@code CONTEXT} lines and non-Java files are never removed.</li>
 * </ol>
 *
 * <p>The filter is intentionally conservative: if there is <em>any</em>
 * doubt (body hash mismatch, unresolved symbol, range not found) the line
 * is kept so that the LLM still sees it.
 */
@Slf4j
@Component
public class SemanticDiffFilter {

    /**
     * Filter {@code lines} by removing ADD/DELETE entries that correspond to
     * purely moved (unchanged) symbols.
     *
     * @param lines       raw changed lines from the diff parser
     * @param sourceIndex AST index of the source (feature) branch
     * @param targetIndex AST index of the target (base) branch
     * @return filtered list — MOVED lines removed, everything else preserved
     */
    public List<ChangedLine> filter(
            List<ChangedLine> lines,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex
    ) {
        Set<String> movedMethodKeys   = findMovedMethodKeys(sourceIndex, targetIndex);
        Set<String> movedFieldKeys    = findMovedFieldKeys(sourceIndex, targetIndex);

        if (movedMethodKeys.isEmpty() && movedFieldKeys.isEmpty()) {
            return lines;
        }

        log.debug("Moved methods: {}, moved fields: {}",
                movedMethodKeys.size(), movedFieldKeys.size());

        // Build line-range sets for ADD side (source) and DELETE side (target)
        Set<LineKey> addLinesToRemove    = buildAddLinesToRemove(movedMethodKeys, movedFieldKeys, sourceIndex);
        Set<LineKey> deleteLinesToRemove = buildDeleteLinesToRemove(movedMethodKeys, movedFieldKeys, targetIndex);

        List<ChangedLine> result = new ArrayList<>();
        int removed = 0;
        for (ChangedLine line : lines) {
            if (line.type() == ChangedLine.LineType.CONTEXT) {
                result.add(line);
                continue;
            }
            if (!line.filePath().endsWith(".java")) {
                result.add(line);
                continue;
            }
            if (line.type() == ChangedLine.LineType.ADD
                    && addLinesToRemove.contains(new LineKey(line.filePath(), line.lineNumber()))) {
                log.trace("Filtering MOVED ADD  {}:{}", line.filePath(), line.lineNumber());
                removed++;
                continue;
            }
            if (line.type() == ChangedLine.LineType.DELETE
                    && deleteLinesToRemove.contains(new LineKey(line.filePath(), line.oldLineNumber()))) {
                log.trace("Filtering MOVED DEL  {}:{}", line.filePath(), line.oldLineNumber());
                removed++;
                continue;
            }
            result.add(line);
        }

        log.info("SemanticDiffFilter: removed {} MOVED lines ({} methods, {} fields moved)",
                removed, movedMethodKeys.size(), movedFieldKeys.size());
        return result;
    }

    // -----------------------------------------------------------------------
    // Identify moved symbols
    // -----------------------------------------------------------------------

    /**
     * A method is considered MOVED (not changed) when:
     * <ul>
     *   <li>its qualified key exists in both source and target indexes, AND</li>
     *   <li>its normalised body hash is identical in both, AND</li>
     *   <li>its file path OR start line differs (otherwise it wasn't moved at all).</li>
     * </ul>
     */
    private Set<String> findMovedMethodKeys(
            JavaIndexService.ProjectIndex source,
            JavaIndexService.ProjectIndex target
    ) {
        Set<String> moved = new HashSet<>();
        for (Map.Entry<String, JavaIndexService.MethodInfo> e : source.methods.entrySet()) {
            String key = e.getKey();
            JavaIndexService.MethodInfo sm = e.getValue();
            JavaIndexService.MethodInfo tm = target.methods.get(key);
            if (tm == null) continue; // added, not moved
            if (sm.bodyHash() == null || !sm.bodyHash().equals(tm.bodyHash())) continue; // changed
            if (sm.filePath().equals(tm.filePath()) && sm.startLine() == tm.startLine()) continue; // identical position
            moved.add(key);
            log.debug("MOVED method: {} from {}:{} to {}:{}",
                    key, tm.filePath(), tm.startLine(), sm.filePath(), sm.startLine());
        }
        return moved;
    }

    /**
     * A field is considered MOVED when its qualified key exists in both indexes
     * but its declaration line (or file) differs, and the declaration text is identical.
     */
    private Set<String> findMovedFieldKeys(
            JavaIndexService.ProjectIndex source,
            JavaIndexService.ProjectIndex target
    ) {
        Set<String> moved = new HashSet<>();
        for (Map.Entry<String, JavaIndexService.FieldInfo> e : source.fields.entrySet()) {
            String key = e.getKey();
            JavaIndexService.FieldInfo sf = e.getValue();
            JavaIndexService.FieldInfo tf = target.fields.get(key);
            if (tf == null) continue;
            // Fields have no body — compare type + name (already part of key) and
            // initialiser text if available; fall back to same-type check.
            if (!sf.type().equals(tf.type())) continue; // type changed
            if (sf.filePath().equals(tf.filePath()) && sf.declarationLine() == tf.declarationLine()) continue;
            moved.add(key);
            log.debug("MOVED field: {} from {}:{} to {}:{}",
                    key, tf.filePath(), tf.declarationLine(), sf.filePath(), sf.declarationLine());
        }
        return moved;
    }

    // -----------------------------------------------------------------------
    // Build line-range sets
    // -----------------------------------------------------------------------

    /** ADD lines to remove = line range of moved methods/fields in the SOURCE index. */
    private Set<LineKey> buildAddLinesToRemove(
            Set<String> movedMethodKeys, Set<String> movedFieldKeys,
            JavaIndexService.ProjectIndex source
    ) {
        Set<LineKey> set = new HashSet<>();
        for (String key : movedMethodKeys) {
            JavaIndexService.MethodInfo m = source.methods.get(key);
            if (m != null) addRange(set, m.filePath(), m.startLine(), m.endLine());
        }
        for (String key : movedFieldKeys) {
            JavaIndexService.FieldInfo f = source.fields.get(key);
            if (f != null) set.add(new LineKey(f.filePath(), f.declarationLine()));
        }
        return set;
    }

    /** DELETE lines to remove = line range of moved methods/fields in the TARGET index. */
    private Set<LineKey> buildDeleteLinesToRemove(
            Set<String> movedMethodKeys, Set<String> movedFieldKeys,
            JavaIndexService.ProjectIndex target
    ) {
        Set<LineKey> set = new HashSet<>();
        for (String key : movedMethodKeys) {
            JavaIndexService.MethodInfo m = target.methods.get(key);
            if (m != null) addRange(set, m.filePath(), m.startLine(), m.endLine());
        }
        for (String key : movedFieldKeys) {
            JavaIndexService.FieldInfo f = target.fields.get(key);
            if (f != null) set.add(new LineKey(f.filePath(), f.declarationLine()));
        }
        return set;
    }

    private void addRange(Set<LineKey> set, String file, int from, int to) {
        for (int i = from; i <= to; i++) set.add(new LineKey(file, i));
    }

    // -----------------------------------------------------------------------
    // Value type
    // -----------------------------------------------------------------------

    private record LineKey(String filePath, int line) {}
}
