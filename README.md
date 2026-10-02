# Markdown Content Pipeline (Java)

A server-side content build and query pipeline that turns a directory of
Markdown source documents into versioned, immutable content products exposed
over HTTP. It supports full rebuilds, incremental rebuilds, consistent
snapshot reads, versioned release with rollback, bounded caching, and
build-failure isolation.

## Build & run

```bash
mvn spring-boot:run
```

The server starts on port `8080` (configurable). Source documents are read
from `pipeline.source-dir` (default `data/sources`); products and versions are
persisted under `pipeline.output-dir` (default `data/output`).

## Source document format

Documents are Markdown files (`.md`). A document may start with a YAML front
matter block:

```markdown
---
title: Getting Started
tags: [guide, intro]
author: bob
---
# Getting Started

See [the reference](reference.md) for details.

@include shared/notice.md
```

- **Front matter** (`title`, `tags`, and arbitrary extra keys) is extracted as
  metadata.
- **`@include <path>`** on its own line inlines another document's rendered
  content. The path is resolved relative to the including document, or against
  the source root when it starts with `/`. A `.md` suffix is appended when
  missing.
- **Internal links** `[text](other.md)` are resolved relative to the document
  and rewritten to content links. External links (`http(s)://`, `mailto:`,
  anchors) are kept as-is.

## Dependency handling

Before every build the complete dependency graph is resolved:

- **Include cycles** (e.g. `a` includes `b` includes `a`) fail the build with
  an `INCLUDE_CYCLE` error locating the source file and line.
- **Missing includes** fail the build with a `MISSING_INCLUDE` error.
- **Missing references** (links to non-existent `.md` files) fail the build by
  default (`MISSING_REFERENCE`). Set `pipeline.missing-ref-policy=WARN` to
  downgrade them to warnings and render the link as external.

A failed build never publishes a version; the last published version stays
readable.

## Version semantics

- Every successful build publishes a new **immutable version** with a
  monotonically increasing id. Versions are never mutated in place.
- A version records: the manifest (document id -> product content hash), the
  per-file source hashes, the whole-tree source fingerprint, the build type
  (`FULL`, `INCREMENTAL`, `ROLLBACK`), and the parent version.
- **Reads** always observe a single committed version (an atomic reference).
  They never take the build lock, so a rebuild cannot block reads and reads
  can never observe a half-built or mixed state.
- **Rollback** to a historical version creates a new `ROLLBACK` version with
  the target's manifest, so reads return exactly the historical content while
  history retains the rollback event. Rolling back to an **archived** version
  (whose products were garbage-collected) fails with a clear error.
- Publish and rollback are serialized by a single lock, so concurrent calls
  have a deterministic total order.

## Incremental rebuilds

`POST /api/build/incremental` scans the source tree, detects added, modified,
and deleted files, and rebuilds only the affected documents:

- A changed document and its directly/transitively affected downstream
  documents are rebuilt.
- Unaffected documents keep their identifiers and content hashes; their
  products are reused from the cache or the previous version.
- Deleted documents are removed from the manifest; renames are treated as
  delete + add, invalidating the old product.
- The incremental result is guaranteed identical to a full build: each
  product's content hash is computed from its own source plus all
  transitively included sources, and rendering is deterministic.

## Build cache

- Products are content-addressed: the cache key is `(document id, content
  hash)`, where the hash covers every input that affects the product (own
  source + transitive includes + the builder version). A cache hit guarantees
  the product is identical to a fresh build.
- Cache size is bounded by an LRU policy (`pipeline.cache-max-entries`).
- Products are persisted on disk content-addressed and shared across versions.
  Products no longer referenced by any retained version are garbage-collected
  promptly; history itself is bounded by `pipeline.version-retention`.

## Configuration

| Key | Default | Description |
| --- | --- | --- |
| `pipeline.source-dir` | `data/sources` | Root directory of Markdown sources |
| `pipeline.output-dir` | `data/output` | Where products and versions are persisted |
| `pipeline.cache-max-entries` | `1000` | Max cached products (LRU) |
| `pipeline.version-retention` | `30` | Max published versions kept in history |
| `pipeline.missing-ref-policy` | `ERROR` | `ERROR` (fail build) or `WARN` (collect warning, continue) |

## HTTP API

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/build/full` | Trigger a full rebuild |
| `POST` | `/api/build/incremental` | Trigger an incremental rebuild |
| `GET` | `/api/content` | List documents in the current version |
| `GET` | `/api/content/{docId}` | Read a document product (id may contain `/`) |
| `GET` | `/api/versions` | List version history |
| `GET` | `/api/versions/{id}` | Version detail with manifest |
| `POST` | `/api/versions/{id}/rollback` | Roll back to a historical version |

## Tests

```bash
mvn test
```

The test suite covers: deterministic products, include/reference resolution,
include cycles, missing references (error and warn), incremental-vs-full
consistency, unaffected-product stability, deletion/rename invalidation,
failure isolation and recovery, concurrent reads during rebuild, concurrent
publish/rollback determinism, and rollback semantics.
