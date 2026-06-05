# Java Engineering MCP Server

A local **Model Context Protocol** server in Java 17 that turns GitHub Copilot
(or any MCP-compatible client) into an engineering assistant for large Java
codebases. It exposes a set of analysis tools — code search, Java AST queries,
git history/diff, and log search — that Copilot can call to understand, debug,
and analyze your repository.

Designed to scale to repositories with millions of lines of Java.

---

## Tools

| Name                  | Purpose                                                                 |
|-----------------------|-------------------------------------------------------------------------|
| `searchCode`          | Recursive text / regex search; skips build & VCS dirs                    |
| `findClass`           | Locate Java classes / interfaces / enums / records                       |
| `findMethod`          | Locate methods (optionally constrained to a class)                       |
| `findCallers`         | Find call sites of `Class#method` via JavaParser symbol solver           |
| `findImplementations` | Find subclasses / interface implementations                              |
| `gitHistory`          | Commit history affecting a file                                          |
| `compareCommits`      | Per-file diff between two revisions with line counts and unified patch   |
| `searchLogs`          | Search `.log` / `.out` / `.txt` files; extracts timestamps               |
| `buildIndex`          | **Code Intelligence:** build/update the persistent SQLite index          |
| `getIndexStatus`      | Phase + counters of the persistent index                                 |
| `traceExecutionFlow`  | Static call tree starting from a method (powered by the index)           |
| `explainMethod`       | Signature + annotations + callers + callees + field accesses             |
| `findDependencyPath`  | Shortest class-to-class dependency path                                   |
| `findServiceEntryPoints` | Controllers, scheduled jobs, listeners, servlets, filters             |
| `findControllerFlows` | Per-endpoint execution trees                                              |
| `findDatabaseAccessPaths` | Entry-points whose flow reaches a JDBC/JPA/Repository sink           |

See [examples/example-requests.json](examples/example-requests.json) for full request/response samples.

---

## Project structure

```
.
├── pom.xml
├── README.md
├── .mcp/
│   └── server.properties.example      # copy to server.properties to override defaults
├── examples/
│   ├── vscode-mcp.json                # drop into .vscode/mcp.json
│   └── example-requests.json
└── src/
    ├── main/
    │   ├── java/com/engasst/mcp/
    │   │   ├── Main.java              # bootstrap & DI wiring
    │   │   ├── config/                # ServerConfig
    │   │   ├── server/                # McpServer (stdio JSON-RPC), Tool, ToolRegistry
    │   │   ├── tools/                 # 8 tool adapters (thin)
    │   │   ├── services/              # business logic (CodeSearch, JavaIndex, Git, Logs)
    │   │   ├── infrastructure/        # RepoWalker, ParsedFileCache
    │   │   └── model/                 # DTO records
    │   └── resources/simplelogger.properties
    └── test/java/...                  # JUnit 5 tests for every service + server loop
```

---

## Architecture

Clean three-layer separation:

```
 MCP client (Copilot)
        │  stdio JSON-RPC 2.0
        ▼
 ┌──────────────────────────┐
 │  server/  McpServer      │   protocol loop: initialize / tools/list / tools/call
 │           ToolRegistry   │
 │           Tool (iface)   │
 └──────────────────────────┘
        │  invokes
        ▼
 ┌──────────────────────────┐
 │  tools/  *Tool adapters  │   JSON ↔ typed arguments; no business logic
 └──────────────────────────┘
        │  delegates to
        ▼
 ┌──────────────────────────┐
 │  services/               │   business logic (search, AST, git, logs)
 └──────────────────────────┘
        │  uses
        ▼
 ┌──────────────────────────┐
 │  infrastructure/         │   RepoWalker (ignore rules), ParsedFileCache (mtime-keyed)
 └──────────────────────────┘
```

DI is constructor-based and wired in [`Main`](src/main/java/com/engasst/mcp/Main.java).
No reflection, no frameworks, easy to test and reason about.

### Adding a new tool

1. Add a service method (e.g. `RunTestsService#run(...)`).
2. Create a class in `tools/` implementing `Tool` (`name`, `description`,
   `inputSchema`, `execute`).
3. Register it in `Main.java`:
   ```java
   registry.register(new RunTestsTool(runTests));
   ```

That is the entire contract. The future tools listed in the requirements
(`traceExecutionFlow`, `runTests`, `findRegressionCandidates`, `searchJira`,
`searchConfluence`) all slot in this way.

---

## Scalability notes

The server is built to operate against multi-million-LOC repositories without
loading the whole tree into memory:

- **Streaming file walker** — `RepoWalker` uses `Files.walkFileTree`, prunes
  ignored directories (`target`, `build`, `node_modules`, `.git`, …) at
  `preVisitDirectory`, and enforces a per-file size cap.
- **Line-by-line reads** — `searchCode` and `searchLogs` never load full files;
  they short-circuit as soon as `maxResults` is reached.
- **mtime-keyed AST cache** — `ParsedFileCache` parses each `.java` file once
  and reuses the `CompilationUnit` until its mtime changes. Repeated calls to
  `findClass` / `findMethod` / `findCallers` are essentially memo-ised.
- **Parallel AST traversal** — `findCallers` and `findImplementations` use a
  bounded thread pool (`mcp.astThreads`, default ≈ CPUs/2).
- **Lazy symbol solver** — JavaParser's `JavaSymbolSolver` is configured only
  on the first AST query that needs it (discovery walks for `src/main/java` and
  `src/test/java` roots), avoiding cost on pure text-search workflows.
- **Heuristic fallback** — symbol resolution fails frequently in workspaces
  without resolved external deps; `findCallers` falls back to import-aware
  heuristic matching and labels each hit `"ast"` or `"heuristic"` so callers
  know the confidence.
- **Bounded responses** — every tool honours a `maxResults` (or per-call patch
  line cap) and truncates long snippets to keep payloads small for the LLM.

For truly enormous monorepos you can additionally:

- Lower `mcp.maxFileSizeBytes` and `mcp.maxResults`.
- Restrict searches via the `path` argument to a module sub-tree.
- Add the directory of generated sources to `mcp.ignoredDirs`.

---

## Configuration

Configuration is loaded in this priority order:

1. JVM system properties: `-Dmcp.maxResults=100`
2. `.mcp/server.properties` at the workspace root
3. Built-in defaults

| Key                     | Default                                            |
|-------------------------|----------------------------------------------------|
| `mcp.ignoredDirs`       | `.git,.hg,.svn,.idea,.vscode,target,build,out,bin,node_modules,dist,.gradle,.mvn` |
| `mcp.textExtensions`    | `java,kt,scala,...,log`                            |
| `mcp.maxFileSizeBytes`  | `5242880` (5 MB)                                   |
| `mcp.maxResults`        | `500`                                              |
| `mcp.astThreads`        | `max(2, CPUs/2)`                                   |

The workspace root is resolved from `--workspace <path>`, then `MCP_WORKSPACE`,
then the current working directory.

---

## Build

Requires JDK 17+ and Maven 3.9+.

```powershell
mvn clean package
```

Using Maven Wrapper (recommended):

```powershell
./mvnw.cmd clean package
```

Produces an executable shaded jar at `target/java-engineering-mcp.jar`.

Run tests:

```powershell
mvn test
```

With wrapper:

```powershell
./mvnw.cmd clean test
```

---

## Requirements

- JDK 17
- Maven 3.9+ (or use the bundled Maven Wrapper)

---

## Run instructions

Build first, then run:

```powershell
./mvnw.cmd clean package
java -jar target/java-engineering-mcp.jar --workspace .
```

---

## Example usage

Manual initialize request over stdio:

```powershell
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | java -jar target/java-engineering-mcp.jar --workspace .
```

---

## Running in VS Code with GitHub Copilot

1. Build the jar (above).
2. Copy [`examples/vscode-mcp.json`](examples/vscode-mcp.json) to
   `.vscode/mcp.json` in any repository where you want Copilot to use the
   server. Adjust the jar path if you want to point at a single shared install
   instead of `${workspaceFolder}/target/...`.
3. Open the repository in VS Code. Copilot Chat will start the MCP server on
   demand and expose all eight tools.
4. Try a prompt such as:
   > "Use findCallers to list every caller of `OrderService#submit`, then
   > summarise which controllers depend on it."

### Standalone (CLI smoke test)

You can drive the protocol manually:

```powershell
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | java -jar target/java-engineering-mcp.jar --workspace .
```

---

## Linux support

The codebase uses only `java.nio.file` and standard process I/O — no Windows
APIs. On Linux, the same jar runs identically; the launcher in
`.vscode/mcp.json` just becomes `"command": "java"` with a Linux-style path.

---

## Security

- The server reads files inside the workspace it is launched against and runs
  `git log` / `git diff` via JGit on the local repo. It does not execute
  arbitrary commands, does not open network sockets, and writes nothing to
  disk.
- All diagnostics go to stderr; stdout is reserved for the JSON-RPC stream.

---

## Code Intelligence Engine

A persistent SQLite-backed code graph that turns the live JavaParser tools
into a queryable database. The earlier tools parsed on every request; the
intelligence engine parses **only changed files** and answers every question
from indexed data.

### Storage

- **SQLite + WAL + recursive CTEs.** Chosen over H2 (slower CTEs, more ops
  surface) and RocksDB (no SQL — we'd hand-roll graph BFS). SQLite gives us
  call-graph and dependency traversal as plain SQL, single-file deploy, and
  excellent tooling (`sqlite3`, DB Browser).
- DB location: `<workspace>/.mcp/intel.db` (auto-created).
- See [`src/main/resources/intel-schema.sql`](src/main/resources/intel-schema.sql) for the full DDL.

### Tables

| Table       | What it stores                                                   |
|-------------|------------------------------------------------------------------|
| `repos`     | One row per indexed repository root.                              |
| `files`     | `(repo_id, path)` + `mtime` + `sha` + `size` — incremental key.   |
| `symbols`   | Classes, interfaces, enums, records, annotations, methods, ctors, fields, packages. `framework_role` carries Spring/Servlet/JPA tags. |
| `annotations` | All annotation usages, with resolved FQN when possible.         |
| `imports`   | Per-file imports, static / wildcard flags.                        |
| `edges`     | The graph: EXTENDS, IMPLEMENTS, CALLS, STATIC_CALL, FIELD_READ, FIELD_WRITE, NEW, ANNOTATED_WITH, THROWS. Unresolved edges keep `(dst_fqn, dst_member)` for the Linker pass. |
| `index_status` | Phase + counters for `getIndexStatus`.                         |

### Pipeline

```
RepoScanner ─(mtime, sha)─► FileChangeSet
                              │
                              ▼
        JavaParser + JavaSymbolSolver (mtime-cached)
                              │
                              ▼
        SymbolExtractor   (writes symbols, annotations, imports, framework_role)
                              │
                              ▼
        RelationshipExtractor (writes edges; resolved → dst_symbol_id;
                               unresolved → (dst_fqn, dst_member))
                              │
                              ▼
        Linker (single UPDATE per edge category — wires dangling edges
                using indexes on (dst_fqn, dst_member, kind))
                              │
                              ▼
                       SQLite (WAL)
```

### Incremental updates

`FileScanner` joins on-disk files against `files` rows by `(mtime, sha)`:

- unchanged → skipped (fast path on mtime only),
- added → parsed,
- modified → `purgeFileContents(fileId)` then re-parsed (cascade drops symbols/edges/annotations/imports for that file),
- removed → row + cascade deletes.

The Linker re-runs every build, so cross-file references that were dangling
in the previous build get resolved as their target files appear.

### Framework detection

Centralised in [`FrameworkDetector`](src/main/java/com/engasst/mcp/intel/pipeline/FrameworkDetector.java).
Annotations recognised: `@RestController`, `@Controller`, `@Service`,
`@Repository`, `@Component`, `@Configuration`, `@Entity`, `@WebServlet`,
`@WebFilter`, `@WebListener`; method-level `@Scheduled`, `@EventListener`,
`@JmsListener`, `@KafkaListener`, `@RabbitListener` and `@*Mapping`. Supertype
heuristics catch `HttpServlet`, `Filter`, `*Listener`, and Spring Data
`JpaRepository` / `CrudRepository` / etc. Roles are stored on the symbol so
entry-point queries are an index seek, not a scan.

### Scalability

Numbers below are for the design target (5M+ LOC, 50k+ classes, 500k+ methods):

| Concern | How we handle it |
|---|---|
| Walking the tree | `RepoWalker` prunes ignored dirs at `preVisitDirectory`; only `.java` files reach the parser. |
| Parse cost | `ParsedFileCache` keeps each `CompilationUnit` keyed by mtime. Incremental builds typically re-parse < 1% of files. |
| Writes | One writer connection guarded by `Database#writeLock()`. Symbol/edge inserts batched in single transactions per file. WAL mode keeps readers non-blocking. |
| Reads | Each query opens a fresh read-only connection. SQLite handles dozens of concurrent readers under WAL. |
| Graph traversal | Pushed entirely into SQL recursive CTEs (`traceExecutionFlow`, `findDependencyPath`). Path-string cycle guard avoids materialising visited sets in the JVM. |
| Result blowup | Every traversal capped by `maxDepth` (default 6, hard limit 25); per-row payloads are flat and short. |
| Symbol resolution failures | Edges retain `(dst_fqn, dst_member)`; Linker pass resolves cross-file later. Each surviving unresolved edge is harmless. |

Indexes (`fqn`, `(kind, simple_name)`, `(src_symbol_id, kind)`, `(dst_symbol_id, kind)`, `(dst_fqn, dst_member, kind)`, `framework_role`) are sized so every entry-point and trace query is index-bound.

Next obvious upgrades (deliberately out of scope for v1):
- Parallel parsing into a SPSC queue feeding the single writer thread.
- Per-package partitioning of `edges` for monorepos > 100M LOC.
- Persistent file-watcher (Java `WatchService`) to keep the index hot.

### Example: `traceExecutionFlow`

Request:

```json
{
  "name": "traceExecutionFlow",
  "arguments": { "className": "CertificateGenerator", "methodName": "generatePKCS12", "maxDepth": 6 }
}
```

Response (shape):

```json
{
  "nodeCount": 7,
  "flow": {
    "class": "com.acme.web.CertificateController",
    "method": "issue", "role": "HTTP_ENDPOINT",
    "file": "src/main/java/com/acme/web/CertificateController.java", "line": 42, "depth": 0,
    "children": [{
      "class": "com.acme.service.CertificateService",
      "method": "issueCertificate", "role": "SERVICE",
      "file": "src/main/java/com/acme/service/CertificateService.java", "line": 81, "depth": 1,
      "children": [{
        "class": "com.acme.crypto.CertificateGenerator",
        "method": "generatePKCS12", "role": null,
        "file": "src/main/java/com/acme/crypto/CertificateGenerator.java", "line": 134, "depth": 2,
        "children": [
          { "class": "com.acme.crypto.KeyPairFactory", "method": "rsa2048",
            "file": "src/main/java/com/acme/crypto/KeyPairFactory.java", "line": 27, "depth": 3, "children": [] },
          { "class": "org.bouncycastle.openssl.jcajce.JcaPKCS8Generator", "method": "<init>",
            "file": null, "line": 156, "depth": 3, "children": [] }
        ]
      }]
    }]
  }
}
```

The `null` file on the BouncyCastle node means it's an external library
the indexer did not see; the edge is recorded against the resolved FQN so
the LLM can still reason about the boundary.

### Setup

```powershell
mvn clean package
# In any target repo:
java -jar path\to\java-engineering-mcp.jar --workspace .
# Then from Copilot, first:    buildIndex
# Subsequent calls (trace, explain, ...) read from .mcp\intel.db
```

---

## License

MIT
