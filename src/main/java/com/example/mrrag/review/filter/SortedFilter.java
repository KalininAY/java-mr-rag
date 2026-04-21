package com.example.mrrag.review.filter;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order
public class SortedFilter implements ContextFilter {
    @Override
    public Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        return lines.stream()
                .sorted(Comparator.comparingInt(ChangedLine::lineNumber))
                .sorted(Comparator.comparing(ChangedLine::filePath))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
