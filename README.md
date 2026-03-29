# java-mr-rag

LLM-assisted Merge Request review enrichment service.

Enriches MR diffs with precise contextual code snippets:
- Changed method call → signature of called method + body
- Deleted declaration → find all usages across project
- Changed method signature → find all callers
- Groups related changes together to minimize LLM context noise

## Stack
- Java 21 + Spring Boot 3.4
- GitLab4J API (MR diff fetching)
- JavaParser + SymbolSolver (AST + symbol resolution)
- Gradle

## Quick Start

```bash
# Set env vars
export GITLAB_URL=https://gitlab.example.com
export GITLAB_TOKEN=glpat-xxxx
export WORKSPACE_DIR=/tmp/mr-rag-workspace

./gradlew bootRun
```

## API

### POST /api/review

Request body:
```json
{
  "projectId": 123,
  "mrIid": 45,
  "sourceBranch": "feature/my-change",
  "targetBranch": "main"
}
```

Response: enriched review context with change groups and contextual snippets.

### GET /api/review/{projectId}/{mrIid}

Same as POST but uses GitLab API to auto-detect branches.

## Architecture

```
MR Diff
  └─ DiffParser          → List<FileDiff>
       └─ ChangeGrouper  → List<ChangeGroup>  (cluster by scope)
            └─ ContextEnricher  → enrichment snippets per group
                 └─ JavaIndexService (JavaParser + SymbolSolver)
```
