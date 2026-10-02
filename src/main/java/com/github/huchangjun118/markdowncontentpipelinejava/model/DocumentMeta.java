package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;
import java.util.Map;

/**
 * Metadata extracted from a document's front matter.
 */
public record DocumentMeta(String title, List<String> tags, Map<String, String> extra) {
}
