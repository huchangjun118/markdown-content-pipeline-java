package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing one source document.
 *
 * @param id          document id
 * @param contentHash SHA-256 of the raw source content
 * @param frontMatter metadata extracted from the leading YAML-ish front matter block
 * @param title       first level-1 heading, or front matter "title", or derived from id
 * @param bodyMarkdown markdown body after front matter removal (include directives preserved)
 * @param includes    include targets in document order (content ids, already normalized)
 * @param links       link target ids that look like content references (existence checked later)
 * @param positions   1-based line numbers keyed by referenced id, for error location
 */
public record ParsedDocument(
        String id,
        String contentHash,
        Map<String, String> frontMatter,
        String title,
        String bodyMarkdown,
        List<String> includes,
        List<String> links,
        Map<String, Integer> positions) {
}
