package com.example.mrrag.review.filter;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RegexFilter implements ContextFilter {
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(static\\s+)?[\\w.]+\\*?;\\s*$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+[\\w.]+;\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        return lines.stream()
                .filter(line -> !IMPORT_PATTERN.matcher(line.content()).matches())
                .filter(line -> !PACKAGE_PATTERN.matcher(line.content()).matches())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
