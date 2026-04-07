package com.example.mrrag.review.spi;

import com.example.mrrag.graph.JavaIndexService;
import com.example.mrrag.review.model.ChangeGroup;

import java.nio.file.Path;
import java.util.List;

/**
 * Enriches change groups with contextual snippets; implemented in {@code app} using
 * {@link com.example.mrrag.graph.AstGraphService}.
 */
public interface ChangeGroupEnrichmentPort {

    List<ChangeGroup> enrich(
            List<ChangeGroup> groups,
            JavaIndexService.ProjectIndex sourceIndex,
            JavaIndexService.ProjectIndex targetIndex,
            Path sourceRepoDir,
            Path targetRepoDir) throws Exception;
}
