# Markdown Content Pipeline

A server-side content build and query service. It builds a directory tree of
Markdown documents into immutable, versioned, content-addressed artifacts and
serves them over HTTP. Both **full** and **incremental** builds are supported,
and reads always observe a single complete committed version.

## Contents

- [Architecture](#architecture)
- [Source format and references](#source-format-and-references)
- [Build semantics](#build-semantics)
  - [Determinism](#determinism)
  - [Dependency graph, cycles and missing references](#dependency-graph-cycles-and-missing-references)
  - [Incremental builds](#incremental-builds)
  - [Build cache](#build-cache)
  - [Failure isolation and recovery](#failure-isolation-and-recovery)
- [Version semantics](#version-semantics)
- [Concurrency](#concurrency)
- [HTTP API](#http-api)
- [Configuration](#configuration)
- [Testing](#testing)

## Architecture

```
 source directory (.md files)
        │
 SourceDirectoryScanner ──► content-hashed SourceSnapshot (id, bytes, sha256)
        │
 MarkdownParser ─────────► front matter, title, include/link refs + line numbers
        │
 DependencyResolver ─────► full graph, strongly connected components (cycles),
        │                    missing reference locations
 DefaultArtifactRenderer ► expanded HTML + content id (cache key)
        │
 BuildCache (bounded LRU, transitive invalidation)
        │
 BuildEngine ────────────► CandidateBuild (full or incremental, deterministic)
        │
 BuildCoordinator (serializes builds)
        │
 VersionStore ───────────► immutable ContentVersion history, atomic current view
        │
 ContentController (HTTP)
```

Key packages:

| Package      | Responsibility |
|--------------|----------------|
| `source`     | Filesystem scanning into content-hashed, sorted snapshots |
| `parser`     | Front matter / title / reference extraction with line positions |
| `graph`      | Complete dependency graph, Kosaraju SCC cycle detection, missing refs |
| `render`     | Deterministic include expansion, link rewriting, content ids |
| `cache`      | Bounded LRU cache keyed by every rendering input |
| `engine`     | Full/incremental assembly, affected-set propagation, topo order |
| `version`    | Immutable versions, monotonic numbers, bounded history, rollback |
| `coordinator`| Single entry point; serializes builds, never blocks reads |
| `web`        | HTTP API |

## Source format and references

A document is a UTF-8 file ending in the configured suffix (default `.md`).
Its **id** is its path relative to the source root using `/` separators
(e.g. `docs/intro.md`).

Optional YAML-ish front matter:

```markdown
---
title: Custom title
author: someone
---
# Otherwise the first H1 is the title
```

Two reference types are understood:

1. **Includes** (content is embedded at build time):

   ```markdown
   {{include parts/section.md}}
   ```

   Paths are resolved relative to the referencing document; a leading `/` is
   relative to the content root. `./` and `../` segments are normalized
   lexically. Anchors (`#...`) are stripped.

2. **Links** (not embedded, rewritten to public URLs):

   ```markdown
   See [the intro](docs/intro.md) or [external](https://example.com).
   ```

   `http(s)://`, `mailto:` and anchor-only links are left untouched. Content
   links are rewritten to `/content/<id>`.

Directives inside fenced code blocks are never interpreted.

## Build semantics

### Determinism

A document's artifact is a pure function of exactly these inputs:

1. the **renderer version** (`content.pipeline.renderer-version`),
2. the active **policies and depth limit**,
3. the document's own **source hash**,
4. the content ids of every document it **transitively includes**, in document
   order, and
5. the **existence** of every document it links to.

These are hashed into the artifact's `contentId` (SHA-256). Consequences:

- Same inputs always produce byte-identical HTML and identical ids.
- Output does not depend on file modification time, locale, environment,
  build order, or concurrency.
- Documents are always processed in one deterministic order: a Kahn
  topological sort on include edges (dependencies first), ties broken by id.
  Cycle-trapped nodes render at a fixed deterministic point and fail/break
  there.

### Dependency graph, cycles and missing references

Before rendering, the whole source set is parsed and the complete graph is
resolved. Cycles are found with an **iterative Kosaraju SCC** pass, so deep
nesting cannot overflow the JVM stack. Each non-trivial strongly connected
component receives an id derived from its sorted member list, making cycle
reporting stable across builds.

`content.pipeline.cycle-policy` (include cycles):

| Policy    | Behavior |
|-----------|----------|
| `FAIL` *(default)* | Every document participating in a cycle fails with a located `CYCLIC_REFERENCE` error (document id + line + SCC id). |
| `BREAK`   | The edge that closes a cycle is replaced with a deterministic `include-cycle` marker and a located warning. |
| `BOUNDED` | Same cycle breaking plus a hard include-depth limit with a deterministic marker. |

`content.pipeline.missing-ref-policy` (include or link target does not exist):

| Policy    | Behavior |
|-----------|----------|
| `FAIL` *(default)* | The referencing document fails with a located `MISSING_REFERENCE` (target id, referrer id, 1-based line). |
| `PLACEHOLDER` | Missing includes are replaced with an `include-missing` marker; links are kept as written; a located warning is recorded. |
| `IGNORE`  | Missing includes are dropped with an HTML comment; a located warning is recorded. |

Cycles and missing references can never hang the build; the graph analysis is
bounded and iterative, and every failure is reported with a document id and a
line number.

### Incremental builds

Given a set of changed files (`ADDED` / `MODIFIED` / `DELETED`), the engine
rebuilds exactly:

- the changed documents, and
- every document that **transitively includes** a changed document, and
- (only for added/deleted files) direct **link** referrers, because link
  rendering depends on target existence rather than linked content.

The reverse adjacency used for propagation is the union of the new graph and
the previous build's graph, so a deleted file's former referrers are still
found.

Properties guaranteed by the implementation and covered by tests:

- Unaffected artifacts (including their `contentId`) are reused byte-for-byte.
- An incremental build produces the **same** artifact set as a full build of
  the same scanned sources.
- Deleted ids never survive into a candidate.
- Renames are expressed as a delete + add; cache entries that embedded the old
  id are invalidated before rendering.
- Reported changes are reconciled against the filesystem: unreported content
  hash changes and deletions are detected automatically.

### Build cache

`BuildCache` is a bounded LRU map. The key is the same content-id hash used
for artifact identity, so it covers the renderer version, policies, own source
hash and the full transitive include closure — a hit can never return stale
content.

- `DELETED`/rename changes call `invalidateAffected(id)`, which evicts the
  document's own entry and every artifact that depends on it, iterating to a
  fixed point over direct-dependency edges.
- LRU eviction bounds memory to `content.pipeline.cache-size`; growth is never
  unbounded in a long-running process.
- Bumping `renderer-version` changes every key, naturally invalidating all
  old entries.

### Failure isolation and recovery

- A candidate containing **fatal** errors is **not published**. The previous
  current version remains available to readers; no half-built state escapes.
- Successful documents in a failed build are warmed in the build cache but are
  not published, so a repair is cheap.
- Errors carry `documentId`, `kind` (`PARSE_ERROR`, `CYCLIC_REFERENCE`,
  `MISSING_REFERENCE`, `RENDER_ERROR`, `IO_ERROR`), 1-based `line`, a
  deterministic `message`, and a `fatal` flag. Non-fatal warnings are returned
  with successful builds.
- After fixing a document, an incremental build only rebuilds that document
  and its downstream set; unrelated artifacts and the already-warmed cache are
  reused.

## Version semantics

Every successful build publishes an immutable `ContentVersion`:

- **Monotonic, never reused version numbers** assigned under a lock.
- Artifacts and error lists are immutable; history entries never mutate.
- History is bounded to `content.pipeline.max-versions` newest versions; the
  current version is always retained.
- **Rollback does not mutate history.** Rolling back to version *n* publishes
  a *new* version whose artifacts are the same immutable objects as *n*.
  Reads after rollback are byte-identical to the historical version, while *n*
  remains queryable until it ages out.
- Rolling back to a version outside the bounded history fails with `404` and
  changes nothing.
- Version N's meaning for the same version number is always "one exact content
  set"; distinct content always gets a distinct number.

A build that fails fatally returns a `BuildResult` with `success=false` and
the current version number, but it does **not** consume a version number.

## Concurrency

- **Builds are serialized** by the coordinator (full, incremental, rollback),
  so two builds never assemble against each other's intermediate state.
- **Reads are lock-free.** `VersionStore` swaps an immutable state object in
  one `AtomicReference` publication; readers dereference that reference and
  see either the old complete version or the new complete version, never a
  mixture.
- Therefore reads never block a build and a build never blocks reads.
- Concurrent publish/rollback operations have a total order defined by monitor
  acquisition; version numbers reflect that order exactly.

## HTTP API

| Method & path | Description |
|---|---|
| `GET /api/content` | Summary of the current version (documents, ids, errors) |
| `GET /api/content/{id}` | Rendered document as HTML (`X-Content-Id` header) |
| `GET /api/documents/{id}` | Rendered document as JSON (html + metadata) |
| `GET /api/versions` | Version history with `current` markers |
| `GET /api/versions/{v}` | One immutable version summary |
| `GET /api/versions/{v}/content/{id}` | One document from an old version |
| `POST /api/build?trigger=...` | Full rebuild (`200` success / `422` fatal errors) |
| `POST /api/build/incremental` | Incremental rebuild; body `{"changes":{"id":"MODIFIED"}}` |
| `POST /api/versions/{v}/rollback` | Atomically republish an old version as the new current |

Change types: `ADDED`, `MODIFIED`, `DELETED`. When the source directory is
scanned, change reports are reconciled with actual file hashes, so a minimal
report is safe.

`BuildResult` JSON shape:

```json
{
  "version": 3,
  "success": true,
  "fullBuild": false,
  "changedDocuments": 1,
  "rebuiltDocuments": 2,
  "reusedArtifacts": 5,
  "errors": []
}
```

## Configuration

All keys are under the prefix `content.pipeline`:

| Key | Default | Meaning |
|---|---|---|
| `source-dir` | `content` | Root directory of markdown sources |
| `source-suffix` | `.md` | File suffix considered a source document |
| `cycle-policy` | `FAIL` | `FAIL` / `BREAK` / `BOUNDED` |
| `missing-ref-policy` | `FAIL` | `FAIL` / `PLACEHOLDER` / `IGNORE` |
| `max-include-depth` | `16` | Include depth safety bound (used by `BOUNDED`) |
| `max-versions` | `10` | Published versions retained for query/rollback |
| `cache-size` | `1024` | Maximum cached artifacts (LRU) |
| `renderer-version` | `1` | Bump on renderer changes; invalidates all keys/ids |
| `build-on-startup` | `true` | Run a synchronous full build during startup |

## Testing

Run all tests:

```bash
mvn test
```

Coverage highlights (32+ JUnit Jupiter tests):

- `DeterminismTest` — independent builds of the same tree are byte-identical;
  adding unrelated files does not change ids.
- `IncrementalConsistencyTest` — incremental equals full for content and ids;
  unaffected identities stable; delete and rename behavior.
- `ReferenceFailureTest` — include cycles (FAIL/BREAK), missing includes and
  links (all policies), located line numbers, recovery rebuilds only affected
  range.
- `VersionRollbackTest` — immutable history, rollback byte-equality, bounded
  history, deterministic outcome of concurrent publish/rollback.
- `ConcurrentReadWriteTest` — readers always observe one complete committed
  version alongside continuous rebuilds.
- `BuildCacheTest` / `CacheKeyTest` — LRU bound, transitive invalidation,
  key coverage and renderer-version invalidation.
- `MarkdownParserTest` / `MarkdownRendererTest` — parsing, normalization and
  deterministic markdown rendering.
- `WebApiIntegrationTest` — full Spring Boot HTTP flow including startup build.
