package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;
import java.util.Map;

/**
 * Complete resolved dependency graph of a source set.
 * Edges point from a document to the documents it depends on (includes / links).
 *
 * @param edges            all references (include + link), values in document order,
 *                         keys in sorted order
 * @param includeEdges     include-only edges, used for include expansion ordering
 * @param cyclicNodes      ids that participate in at least one include cycle
 * @param cyclicScc        id of the strongly connected component for every cyclic
 *                         node; the scc id is derived from the sorted member list
 *                         and therefore stable across builds
 * @param missingReferences missing target id -> referrer locations (sorted)
 */
public record DependencyGraph(
        Map<String, List<String>> edges,
        Map<String, List<String>> includeEdges,
        List<String> cyclicNodes,
        Map<String, String> cyclicScc,
        Map<String, List<ReferenceLocation>> missingReferences) {

    public record ReferenceLocation(String referrerId, int line) {
    }
}
