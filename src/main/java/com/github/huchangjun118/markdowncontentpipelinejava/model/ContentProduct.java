package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.Set;

/**
 * The built product of a single document.
 *
 * @param contentHash deterministic hash over all inputs that affect the product
 * @param dependencies docs whose content transitively feeds this product
 */
public record ContentProduct(
        DocId docId,
        String sourcePath,
        String title,
        String html,
        String contentHash,
        Set<DocId> dependencies) {
}
