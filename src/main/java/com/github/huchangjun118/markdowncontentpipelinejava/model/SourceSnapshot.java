package com.github.huchangjun118.markdowncontentpipelinejava.model;


/**
 * Immutable snapshot of one source document at build time.
 *
 * @param id        document id, the content-relative path with normalized '/' separators
 * @param content   raw file content (UTF-8)
 * @param contentHash SHA-256 hex of the raw content
 * @param lastModified last modified time in epoch millis (informational only, never part of keys)
 * @param size      file size in bytes
 */
public record SourceSnapshot(String id, String content, String contentHash, long lastModified, long size) {
    public static SourceSnapshot of(String id, String content, String contentHash, long lastModified, long size) {
        return new SourceSnapshot(id, content, contentHash, lastModified, size);
    }
}
