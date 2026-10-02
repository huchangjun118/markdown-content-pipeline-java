package com.github.huchangjun118.markdowncontentpipelinejava.model;

/**
 * Located, immutable build diagnostic for a single document.
 *
 * @param documentId document that owns the offending reference (the missing
 *                   target id is embedded in the message when applicable)
 * @param kind       error kind
 * @param line       1-based source line, or 0 when not applicable
 * @param message    human readable, deterministic message
 * @param fatal      true when the document could not be published; false for a
 *                   located warning produced under a non-fatal policy
 */
public record BuildError(String documentId, ErrorKind kind, int line, String message, boolean fatal) {

    public static BuildError fatal(String documentId, ErrorKind kind, int line, String message) {
        return new BuildError(documentId, kind, line, message, true);
    }

    public static BuildError warning(String documentId, ErrorKind kind, int line, String message) {
        return new BuildError(documentId, kind, line, message, false);
    }
}
