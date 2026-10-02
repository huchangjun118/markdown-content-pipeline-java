package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;

/**
 * A parsed source document: metadata plus the raw directives/links it contains.
 */
public record ParsedDocument(
        DocId id,
        String sourcePath,
        DocumentMeta meta,
        String rawContent,
        String body,
        List<RawRef> includes,
        List<RawRef> references) {

    /**
     * A raw reference found in the source before resolution.
     */
    public record RawRef(String target, int line, Kind kind) {
        public enum Kind { INCLUDE, REFERENCE }
    }
}
