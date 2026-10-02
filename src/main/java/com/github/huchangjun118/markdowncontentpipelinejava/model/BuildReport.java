package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;

/**
 * Result of a build attempt. A failed build never publishes a version.
 */
public record BuildReport(
        boolean success,
        Long versionId,
        List<BuildError> errors,
        List<BuildError> warnings,
        List<DocId> built,
        List<DocId> reused,
        List<DocId> affected) {

    public static BuildReport failure(List<BuildError> errors, List<BuildError> warnings) {
        return new BuildReport(false, null, List.copyOf(errors), List.copyOf(warnings),
                List.of(), List.of(), List.of());
    }
}
