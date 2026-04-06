package com.example.mrrag.review.model;

public record ReviewStats(
        int totalChangedLines,
        int totalGroups,
        int totalEnrichmentSnippets,
        int totalEnrichmentLines
) {}
