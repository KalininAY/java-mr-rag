package com.example.mrrag.model;

public record ReviewStats(
        int totalChangedLines,
        int totalGroups,
        int totalEnrichmentSnippets,
        int totalEnrichmentLines
) {}
