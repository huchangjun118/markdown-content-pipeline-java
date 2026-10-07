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
readable. A document that fails to parse or render fails the whole build with
a locatable error (`sourcePath` + error type) and never corrupts the published
state; after the source is fixed, the next incremental build rebuilds exactly
the affected range.

### Link existence is part of the product

Whether an internal link resolves to an existing document changes the rendered
HTML (`href="content/<id>"` vs. the raw target). The pipeline therefore treats
**link resolution as build input**: deleting, renaming or re-adding a linked
document changes the rendered output of every document that links to it, and
those documents get new product identifiers (see below). Under
`pipeline.missing-ref-policy=WARN` this means an incremental rebuild after
such a change produces exactly the same products and identifiers as a full
rebuild of the same source directory.

## Product identifier semantics

Every product carries a content hash that identifies it. The hash is a pure
function of **every input that affects the rendered output**:

- the builder/render version;
- the document id and source path (a product is bound to its identity, so two
  identical files at different paths still get distinct identifiers);
- the document's own raw source;
- the link-resolution fingerprint: for every internal `.md` link, which
  document it resolves to, or that it is missing;
- the product hashes of all directly included documents (which recursively
  covers the whole include chain).

Consequences:

- **Same identifier, same content.** Two different products never share an
  identifier; the version store refuses to publish a colliding product.
- **Content change ⟹ identifier change.** Any change in rendered output -
  own edits, an included document's edit at any depth, or a link target
  appearing/disappearing - produces a new identifier.
- **No change ⟹ same identifier.** Unaffected documents keep their
  identifiers across incremental rebuilds, and identical inputs always
  reproduce the same identifiers regardless of build mode, order, or
  concurrency.
- **Rollback safety.** Because an identifier denotes exactly one immutable
  product, a historical version can never observe content built later;
  rolling back reads exactly what the version published.

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

- A changed document and the documents that transitively include it are
  rebuilt (included content is inlined into the referrer's product).
- An added or deleted document additionally invalidates the documents whose
  links to it start or stop resolving (one reference hop), and those
  referrers' include upstreams. Deleted-link referrers are found by
  re-resolving references against the deleted paths, so this works even right
  after a restart.
- Unaffected documents keep their identifiers and content hashes; their
  products are reused from the cache or the previous version. A document that
  merely links to a *modified* document is not rebuilt, because link targets'
  content does not feed the referrer's product.
- Deleted documents are removed from the manifest; renames are treated as
  delete + add, invalidating the old product.
- With no source changes, an incremental build is a no-op and publishes no new
  version.
- The incremental result is guaranteed identical to a full build of the same
  source directory: each product's identifier is computed from all of its
  inputs (see *Product identifier semantics*), so any document whose output
  would differ is rebuilt, and rendering is deterministic.

## Build cache

- Products are content-addressed: the cache key is `(document id, content
  hash)`, where the hash covers every input that affects the product (see
  *Product identifier semantics*). A cache hit guarantees the product is
  identical to a fresh build, so stale entries can never be served for a new
  state - after a link-existence change the document's identifier changes and
  old entries simply become unreachable.
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
consistency (including against a from-scratch full build in a fresh output
directory), product-identifier stability and change semantics, link-existence
changes (delete/rename/re-add of a linked document, including after a
restart), delete → rebuild → rollback → re-add sequences, nested
include/link affected-range precision, identical sources at different paths,
failure isolation and recovery, bounded cache behaviour, concurrent reads
during rebuild, concurrent rebuild/read/rollback interleavings, and rollback
semantics.

### Verifying the guarantees by hand

1. Start with `pipeline.missing-ref-policy=WARN` and a document `index.md`
   linking to `guide.md`; build fully and note `index`'s `contentHash` via
   `GET /api/content/index`.
2. Delete `guide.md` and run `POST /api/build/incremental`: `index`'s hash
   must have changed and its link must render as `href="guide.md"`.
3. Run `POST /api/build/full`: the manifest must be identical to the
   incremental result.
4. `POST /api/versions/{firstVersionId}/rollback`: `index` must serve the
   original content with `href="content/guide"` again.
5. Re-add `guide.md` and rebuild incrementally: the manifest must equal the
   very first version's manifest.
