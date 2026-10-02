package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;

/**
 * Outcome of a build request against the coordinator.
 */
public record BuildResult(
        long version,
        boolean success,
        boolean fullBuild,
        int changedDocuments,
        int rebuiltDocuments,
        int reusedArtifacts,
        List<BuildError> errors) {
}
