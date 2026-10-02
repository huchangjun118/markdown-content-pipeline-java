package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.Map;

/**
 * An immutable published version.
 *
 * @param manifest        doc id -> product content hash
 * @param fileHashes      source relative path -> file sha256 at build time
 * @param sourceFingerprint hash of the whole source tree
 * @param state          ACTIVE or ARCHIVED (products may have been GC'd)
 */
public record BuildVersion(
        long versionId,
        VersionType type,
        Long parentVersionId,
        Long sourceVersionId,
        Map<DocId, String> manifest,
        Map<String, String> fileHashes,
        String sourceFingerprint,
        long createdAt,
        State state) {

    public enum VersionType { FULL, INCREMENTAL, ROLLBACK }

    public enum State { ACTIVE, ARCHIVED }
}
