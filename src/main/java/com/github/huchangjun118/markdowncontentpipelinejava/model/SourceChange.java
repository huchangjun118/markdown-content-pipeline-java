package com.github.huchangjun118.markdowncontentpipelinejava.model;

/**
 * A detected source tree change between two scans.
 */
public record SourceChange(Type type, String relativePath, DocId docId, String newSha256) {

    public enum Type { ADDED, MODIFIED, DELETED }
}
