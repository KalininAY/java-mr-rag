# java-mr-rag

LLM-assisted Merge Request review enrichment service.

Enriches MR diffs with precise contextual code snippets retrieved from a
Spoon-based AST symbol graph:

- Changed method call → signature + body of the called method
- Deleted declaration → all usages across the project
- Changed method signature → all callers
- Groups related changes together to minimise LLM context noise

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 + Spring Boot 3.4 |
| AST / symbol graph | [Spoon](https://spoon.gforge.inria.fr/) |
| MR metadata | GitLab4J API |
| Build | Gradle |

---

## Quick Start

### 1. Configure environment

```bash
export GITLAB_URL=https://gitlab.example.com
export GITLAB_TOKEN=glpat-xxxx
export WORKSPACE_DIR=/tmp/mr-rag-workspace   # local repo checkout directory
```

### 2. Run

```bash
./gradlew bootRun
```

### 3. Index a repository and build the graph

Send the URL of any Git repository — the service clones it into a temporary
directory, builds the Spoon AST graph, then deletes the clone.

```bash
# Shallow-clone the default branch and build the graph
curl -s -X POST http://localhost:8080/api/graph/ingest \
     -H "Content-Type: application/json" \
     -d '{"repoUrl": "https://github.com/org/my-service.git"}' \
     | jq .
```

```bash
# Clone a specific branch
curl -s -X POST http://localhost:8080/api/graph/ingest \
     -H "Content-Type: application/json" \
     -d '{"repoUrl": "https://github.com/org/my-service.git", "branch": "develop"}' \
     | jq .
```

**Example response:**

```json
{
  "repoUrl":    "https://github.com/org/my-service.git",
  "cloneDir":   "/tmp/mrrag-clone-4521346789",
  "cloneMs":    3241,
  "buildMs":    8703,
  "totalMs":    11984,
  "totalNodes": 1842,
  "totalEdges": 9374,
  "nodesByKind": {
    "CLASS": 120, "METHOD": 980, "CONSTRUCTOR": 87,
    "FIELD": 310, "VARIABLE": 233, "LAMBDA": 64,
    "ANNOTATION": 18, "TYPE_PARAM": 21, "ANNOTATION_ATTRIBUTE": 9
  },
  "edgesByKind": {
    "DECLARES": 1100, "INVOKES": 4200, "EXTENDS": 45,
    "IMPLEMENTS": 112, "READS_FIELD": 830, "WRITES_FIELD": 310,
    "ANNOTATED_WITH": 540, "REFERENCES_TYPE": 1890, "OVERRIDES": 97,
    "THROWS": 155, "INSTANTIATES": 95
  },
  "uniqueFiles": 87
}
```

### 4. Run an MR review

```bash
# Auto-detect branches from GitLab MR metadata
curl http://localhost:8080/api/review/123/45

# Full review context as JSON (explicit branches)
curl -s -X POST http://localhost:8080/api/review \
     -H "Content-Type: application/json" \
     -d '{
           "projectId": 123,
           "mrIid": 45,
           "sourceBranch": "feature/my-change",
           "targetBranch": "main"
         }'

# Human-readable Markdown output
curl http://localhost:8080/api/review/123/45/markdown
```

---

## API Reference

### Graph ingestion

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/graph/ingest` | Clone repo, build graph, return stats |

**Request body:**

```json
{ "repoUrl": "https://...", "branch": "main" }
```

`branch` is optional; omit to use the repository's default branch.

---

### MR Review

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/review` | Full review context (JSON) |
| `GET`  | `/api/review/{projectId}/{mrIid}` | Auto-detect branches via GitLab API |
| `GET`  | `/api/review/{projectId}/{mrIid}/markdown` | Human-readable Markdown |

---

### Debug endpoints

These endpoints operate on a **locally checked-out** repository directory
(the `repoDir` parameter is an absolute filesystem path).  
Use them to inspect the graph without going through the full MR review pipeline.

#### `GET /debug/graph/stats`

Overall graph statistics — node/edge counts and the list of indexed files.

```bash
curl "http://localhost:8080/debug/graph/stats?repoDir=/tmp/my-repo"
```

#### `GET /debug/graph/file`

Resolve a GitLab diff path to its graph-normalized form and list all nodes
in that file.

```bash
curl "http://localhost:8080/debug/graph/file\
?repoDir=/tmp/my-repo\
&diffPath=gl-hooks/src/main/java/com/example/Foo.java"
```

#### `GET /debug/graph/line`

Show all graph nodes whose line-range covers a given line, plus all outgoing
edges from those nodes at exactly that line.

```bash
curl "http://localhost:8080/debug/graph/line\
?repoDir=/tmp/my-repo\
&diffPath=src/main/java/com/example/Foo.java\
&line=42"
```

---

## Debug: building a ViewGraph in code

[`GraphViewBuilder`](src/main/java/com/example/mrrag/service/GraphViewBuilder.java)
wraps the raw `ProjectGraph` in a fully cross-linked object graph.
Inject it into any Spring bean and use it for direct traversal:

```java
@Autowired AstGraphService   graphService;
@Autowired GraphViewBuilder  viewBuilder;

// 1. Build the raw symbol graph for a local checkout
ProjectGraph projectGraph = graphService.buildGraph(Path.of("/tmp/my-repo"));

// 2. Wrap it in a cross-linked view
GraphViewBuilder.ViewGraph vg = viewBuilder.build(projectGraph);

// 3. Look up a class by its fully-qualified name
ClassNodeView foo = vg.classById("com.example.Foo");
if (foo != null) {

    // All methods declared on the class
    foo.getMethods().forEach(m ->
        System.out.printf("  method: %s (%d callers)%n",
            m.getSimpleName(), m.getCallers().size()));

    // All classes that extend Foo
    foo.getSubClasses().forEach(sub ->
        System.out.println("  subclass: " + sub.getId()));

    // All call-sites that reference the Foo type
    foo.getReferencedBy().forEach(ref ->
        System.out.println("  referenced by: " + ref.getId()));
}

// 4. Iterate all methods that override something
vg.allMethods().stream()
    .filter(m -> m.getOverrides() != null)
    .forEach(m -> System.out.printf(
        "%s  overrides  %s%n", m.getId(), m.getOverrides().getId()));

// 5. Find classes with more than 20 callers on any single method
vg.allClasses().stream()
    .flatMap(c -> c.getMethods().stream())
    .filter(m -> m.getCallers().size() > 20)
    .sorted(Comparator.comparingInt(m -> -m.getCallers().size()))
    .forEach(m -> System.out.printf(
        "hot method: %s — %d callers%n", m.getId(), m.getCallers().size()));

// 6. Invalidate the cache after a re-checkout
graphService.invalidate(Path.of("/tmp/my-repo"));
```

---

## Architecture

```
MR Diff
  └─ DiffParser              → List<FileDiff>
       └─ ChangeGrouper      → List<ChangeGroup>     (cluster by scope)
            └─ ContextEnricher  → enrichment snippets per group
                 ├─ JavaIndexService (JavaParser + SymbolSolver)
                 └─ AstGraphService (Spoon AST)
                       └─ GraphViewBuilder            → ViewGraph (cross-linked views)
```

### Graph node kinds

`CLASS` · `CONSTRUCTOR` · `METHOD` · `FIELD` · `VARIABLE` · `LAMBDA` ·
`ANNOTATION` · `TYPE_PARAM` · `ANNOTATION_ATTRIBUTE`

### Graph edge kinds

`DECLARES` · `EXTENDS` · `IMPLEMENTS` · `INVOKES` · `INSTANTIATES` ·
`INSTANTIATES_ANONYMOUS` · `REFERENCES_METHOD` · `READS_FIELD` ·
`WRITES_FIELD` · `READS_LOCAL_VAR` · `WRITES_LOCAL_VAR` · `THROWS` ·
`ANNOTATED_WITH` · `REFERENCES_TYPE` · `OVERRIDES` · `HAS_TYPE_PARAM` ·
`HAS_BOUND` · `ANNOTATION_ATTR`

Each edge kind can be toggled via `application.properties`:

```properties
graph.edge.INVOKES.enabled=true
graph.edge.READS_LOCAL_VAR.enabled=false
```
