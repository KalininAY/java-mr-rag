package com.example.mrrag.review.filter;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EmptyFilter implements ContextFilter {
    @Override
    public Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        return lines.stream()
                .filter(it -> !it.content().isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
