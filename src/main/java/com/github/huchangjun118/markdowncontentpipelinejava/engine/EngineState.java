package com.github.huchangjun118.markdowncontentpipelinejava.engine;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;

import java.util.Map;

/**
 * Everything the engine needs from the last build to compute an incremental
 * candidate. Kept privately by the engine and never exposed mid-build.
 */
public record EngineState(
        Map<String, SourceSnapshot> sources,
        Map<String, ParsedDocument> parsed,
        Map<String, BuildArtifact> artifacts,
        Map<String, String> cacheKeys) {

    public static EngineState empty() {
        return new EngineState(Map.of(), Map.of(), Map.of(), Map.of());
    }
}
