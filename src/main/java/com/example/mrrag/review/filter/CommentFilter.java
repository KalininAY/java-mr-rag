package com.example.mrrag.review.filter;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class CommentFilter implements ContextFilter {
    @Override
    public Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        Map<String, List<ChangedLine>> byFile = new LinkedHashMap<>();
        for (ChangedLine l : lines) byFile.computeIfAbsent(l.filePath(), k -> new ArrayList<>()).add(l);
        Set<ChangedLine> result = new LinkedHashSet<>();
        for (List<ChangedLine> fileLines : byFile.values()) result.addAll(mergeMirrorInFile(fileLines));
        return result;
    }


    private List<ChangedLine> mergeMirrorInFile(List<ChangedLine> lines) {
        int n = lines.size();
        boolean[] consumed = new boolean[n];
        List<ChangedLine> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (consumed[i]) continue;
            ChangedLine del = lines.get(i);
            if (del.type() != ChangedLine.LineType.DELETE) {
                out.add(del);
                continue;
            }
            String delNorm = normaliseForMirror(del.content());
            boolean paired = false;
            for (int j = i + 1; j < Math.min(n, i + 4); j++) {
                if (consumed[j]) continue;
                ChangedLine add = lines.get(j);
                if (add.type() != ChangedLine.LineType.ADD) continue;
                String addNorm = normaliseForMirror(uncomment(add.content()));
                if (delNorm.equals(addNorm) && !delNorm.isBlank()) {
                    consumed[j] = true;
                    consumed[i] = true;
                    out.add(del.asContext());
                    out.add(add);
                    paired = true;
                    log.trace("Mirror pair merged: '{}'", delNorm);
                    break;
                }
            }
            if (!paired) out.add(del);
        }
        return out;
    }


    private static String uncomment(String text) {
        if (text == null) return "";
        String s = text.strip();
        return s.startsWith("//") ? s.substring(2).strip() : s;
    }

    private static String normaliseForMirror(String text) {
        if (text == null) return "";
        return uncomment(text).toLowerCase(java.util.Locale.ROOT);
    }
}
