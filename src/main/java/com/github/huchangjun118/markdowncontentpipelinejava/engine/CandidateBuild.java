package com.github.huchangjun118.markdowncontentpipelinejava.engine;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;

import java.util.List;
import java.util.Map;

/**
 * A fully assembled but not yet published build: complete artifact set and
 * located errors. It either becomes an immutable version or is discarded;
 * readers can never observe it.
 */
public record CandidateBuild(
        Map<String, BuildArtifact> artifacts,
        List<BuildError> errors,
        Map<String, SourceSnapshot> sources,
        int rebuiltCount,
        int reusedCount,
        int changedCount) {
}
