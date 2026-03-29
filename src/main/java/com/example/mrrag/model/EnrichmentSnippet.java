package com.example.mrrag.model;

import java.util.List;

/**
 * A contextual code snippet attached to a ChangeGroup to help the LLM understand impact.
 */
public record EnrichmentSnippet(
        SnippetType type,
        String filePath,
        int startLine,
        int endLine,
        String symbolName,
        List<String> lines,
        String explanation
) {
    public enum SnippetType {
        METHOD_SIGNATURE,
        METHOD_BODY,
        METHOD_CALLERS,
        FIELD_USAGES,
        VARIABLE_USAGES,
        DECLARATION
    }
}
