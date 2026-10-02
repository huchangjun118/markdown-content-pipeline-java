package com.github.huchangjun118.markdowncontentpipelinejava.render;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders one document artifact deterministically. The artifact identity
 * covers the renderer version, the source hash and the identities of every
 * transitive include in topological order.
 */
public interface ArtifactRenderer {

    /**
     * Cache key covering every input that can affect the rendered artifact.
     * Returns {@code null} when the document cannot possibly produce a
     * cacheable artifact under the current policies (fatal cycle/missing).
     *
     * @param keyMemo per-build memo of already computed keys (shared across
     *                documents of one build; never across builds)
     */
    String cacheKey(
            String documentId,
            Map<String, ParsedDocument> parsed,
            DependencyGraph graph,
            Map<String, String> keyMemo);

    /**
     * @return rendered artifact, or empty when the document cannot be rendered
     *         (fatal cycle/missing, render exception); errors explain why.
     *         Warnings are also appended under non-fatal policies.
     */
    Optional<BuildArtifact> render(
            String documentId,
            Map<String, ParsedDocument> parsed,
            Map<String, BuildArtifact> rendered,
            DependencyGraph graph,
            List<BuildError> errors);
}
