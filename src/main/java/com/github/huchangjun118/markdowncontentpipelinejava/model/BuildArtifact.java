package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.Map;

/**
 * Immutable built artifact of one document.
 *
 * @param documentId source document id
 * @param contentId  content-addressed identity; identical inputs always yield the same id
 * @param html       fully rendered HTML (includes expanded, links resolved)
 * @param title      resolved title
 * @param metadata   front matter metadata
 * @param dependencies  ids this artifact was rendered from, direct dependencies in sorted order
 */
public record BuildArtifact(
        String documentId,
        String contentId,
        String html,
        String title,
        Map<String, String> metadata,
        java.util.List<String> dependencies) {
}
