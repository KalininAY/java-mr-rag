package com.example.mrrag.review.filter;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;

import java.util.Set;

/**
 * Определяет метод для фильтрации изменных строк из Diff Merge Request
 */
public interface ContextFilter {

    /**
     * Фильтрация измененных строк из MR
     *
     * @param lines       строка/линия из Diff MR
     * @param sourceGraph исходный граф
     * @param targetGraph текущий граф
     * @return отфильрованные строки/линии
     */
    Set<ChangedLine> filter(Set<ChangedLine> lines, ProjectGraph sourceGraph, ProjectGraph targetGraph);
}
