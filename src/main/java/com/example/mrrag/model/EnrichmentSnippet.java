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
        /** Signature lines of a called/changed method */
        METHOD_SIGNATURE,
        /** Full (or truncated) body of a method */
        METHOD_BODY,
        /** List of sites that call a changed method */
        METHOD_CALLERS,
        /** Declaration of a field + its write sites */
        FIELD_USAGES,
        /** All usages of a local variable */
        VARIABLE_USAGES,
        /** Declaration of a field/variable */
        DECLARATION,
        /** Callee parameter names when arguments were changed */
        ARGUMENT_CONTEXT
    }
}
