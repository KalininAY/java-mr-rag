package com.example.mrrag.service;

import com.example.mrrag.model.graph.ProjectGraph;

import java.util.List;

public interface AstGraphProvider {

    ProjectGraph buildGraph(String projectId, List<String> source);

    void invalidate(String projectId);
}
