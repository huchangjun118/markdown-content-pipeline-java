package com.github.huchangjun118.markdowncontentpipelinejava.model;

/**
 * A structured, locatable build error or warning.
 */
public record BuildError(
        String sourcePath,
        int line,
        ErrorType type,
        Severity severity,
        String message) {

    public enum ErrorType {
        PARSE_ERROR,
        MISSING_INCLUDE,
        MISSING_REFERENCE,
        INCLUDE_CYCLE,
        DUPLICATE_DOC_ID,
        RENDER_ERROR
    }

    public enum Severity {
        ERROR, WARN
    }

    public static BuildError error(String sourcePath, int line, ErrorType type, String message) {
        return new BuildError(sourcePath, line, type, Severity.ERROR, message);
    }

    public static BuildError warn(String sourcePath, int line, ErrorType type, String message) {
        return new BuildError(sourcePath, line, type, Severity.WARN, message);
    }
}
