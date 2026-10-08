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

### Reference existence under `WARN` is part of the build

When `pipeline.missing-ref-policy=WARN`, a link's rendered form depends on
whether its target currently exists:

- target present  → `<a href="content/<id>">…</a>`
- target missing  → `<a href="raw-target.md">…</a>` (left external, one warning)

That **existence/resolution is a build input**. Deleting, renaming, or
re-adding a referenced document therefore changes the rendered content of
every document that references it (directly or through an `@include` chain),
even though the referencing source bytes are untouched. The pipeline handles
this end to end:

- Every such referencing document gets a **new product identifier** on the
  next (incremental) build. One identifier can never name two different
  contents; publishing refuses to overwrite an identifier with different
  content (an in-memory and on-disk integrity check).
- An incremental build over the changed source tree produces exactly the same
  manifest and products as a full build of that same tree.
- Historical versions remain immutable: after deleting the target and
  rebuilding, rolling back to a version from when it existed returns exactly
  the old content, and later builds never mutate products that a historical
  version points to.

## Version semantics

- Every successful build publishes a new **immutable version** with a
  monotonically increasing id. Versions are never mutated in place.
- A version records: the manifest (document id -> product content hash), the
  per-file source hashes, the whole-tree source fingerprint, the build type
  (`FULL`, `INCREMENTAL`, `ROLLBACK`), and the parent version.
- **Product identifier semantics.** The `contentHash` stored in the manifest
  and in each product is a deterministic digest of **every input that affects
  the rendered output**: the builder/render version, the document's own raw
  source, the raw sources of all transitively included documents, and — for
  the document and every document in its include closure — the resolution of
  every `@include` and internal `.md` link (which document id it points to, or
  "unresolved"). Consequently:
  - different rendered content always has a different identifier (in
    particular, changing link/include existence re-identifies every affected
    document);
  - identical inputs across builds and across separate deployments yield
    identical identifiers;
  - products are content-addressed and safely shared between versions, and the
    store rejects any attempt to bind two different contents to one
    identifier.
- **Reads** always observe a single committed version (an atomic reference).
  They never take the build lock, so a rebuild cannot block reads and reads
  can never observe a half-built or mixed state. This also holds while
  reference churn (delete/rename/readd) is interleaved with concurrent reads,
  publishes and rollbacks.
- **Rollback** to a historical version creates a new `ROLLBACK` version with
  the target's manifest, so reads return exactly the historical content while
  history retains the rollback event. Rolling back to an **archived** version
  (whose products were garbage-collected) fails with a clear error. Any number
  of later rebuilds leave the historical version's products unchanged;
  consistency is preserved across restarts because products are persisted
  content-addressed.
- Publish and rollback are serialized by a single lock, so concurrent calls
  have a deterministic total order. If a rollback target is archived by a
  racing publish before the lock is taken, the rollback is rejected
  explicitly (HTTP `409`) and the current version is unchanged.

## Incremental rebuilds

`POST /api/build/incremental` scans the source tree, detects added, modified,
and deleted files, and rebuilds only the affected documents:

- A document is **affected** exactly when its newly computed product
  fingerprint differs from the identifier the previous committed version
  records for it (new documents count as affected). Because the fingerprint
  includes link/include resolution in the whole render closure, deleting,
  renaming or re-adding a referenced document flags precisely the documents
  whose rendering changed — including indirect dependents through nested
  include/link graphs — without relying on any in-memory state, so the result
  is correct after a restart or a rollback.
- Unaffected documents keep their identifiers and content; the exact
  previously published product is reused (and may additionally be served from
  the LRU build cache), so documents unrelated to the change are never
  rebuilt.
- Deleted documents are removed from the manifest and their cache entries are
  evicted; renames are treated as delete + add.
- With no source changes at all, an incremental build is a no-op: it returns
  the current version id and publishes nothing.
- The incremental result is guaranteed identical to a full build, manifest
  entry by manifest entry and product by product. Repeated
  delete/rebuild/rollback/readd sequences converge to exactly what a full
  build of the final source tree produces.
- A single document that fails to render produces a locatable `RENDER_ERROR`
  (with the source path) and fails the build without publishing anything; the
  last published version stays readable, and after the source is fixed only
  the affected range is rebuilt.

## Build cache

- The build cache is keyed by document id and the same content fingerprint
  described above, covering the document's own source, its transitive
  includes, and link/include resolution. A cache hit guarantees the product
  is identical to a fresh render, so reference-existence changes never serve a
  stale product.
- Cache size is bounded by an LRU policy (`pipeline.cache-max-entries`).
- Products are persisted on disk content-addressed and shared across versions.
  Products no longer referenced by any retained version are garbage-collected
  promptly (including products of deleted/renamed documents); history itself
  is bounded by `pipeline.version-retention`.

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

The suite covers: deterministic products, include/reference resolution,
include cycles, missing references (error and warn), incremental-vs-full
consistency, unaffected-product stability, deletion/rename invalidation,
failure isolation and recovery, concurrent reads during rebuild, concurrent
publish/rollback determinism, and rollback semantics.

Reference-existence hazards are specifically covered by
`MissingRefIncrementalReproTest`, `MissingReferenceAcceptanceTest`,
`PersistenceConsistencyTest`, `RenderFailureIsolationTest` and the churn case
in `ConcurrencyTest`. They assert, under `missing-ref-policy=WARN`:

1. deleting/renaming/re-adding a referenced document changes the referencing
   products' identifiers and rendered links; two contents never share an id;
2. the incremental result equals a full build of the same tree, document by
   document (manifest and HTML);
3. rollback to any historical version reads exactly the content it was
   published with, remains so after arbitrarily many later rebuilds, and
   survives a process restart;
4. unrelated documents keep identical identifiers/content, and a tree with no
   changes makes an incremental build a no-op;
5. a delete → incremental → rollback → readd → incremental sequence is
   self-consistent at every step and converges to the full-build state;
6. deeply nested mixed include/link graphs rebuild exactly the affected set
   and never unrelated documents;
7. concurrent readers always observe one complete committed version while
   churn, publishes and rollbacks interleave, without builds blocking reads;
8. the build cache stays bounded and never serves stale products;
9. a render/dependency failure yields a locatable error, does not publish or
   pollute the current version, and recovery rebuilds only the affected range.
