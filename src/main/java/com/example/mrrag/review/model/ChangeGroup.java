package com.example.mrrag.review.model;

import java.util.List;

/**
 * A cluster of related changed lines that should be reviewed together.
 */
public record ChangeGroup(
        String id,
        String primaryFile,
        List<ChangedLine> changedLines,
        List<EnrichmentSnippet> enrichments
) {}
