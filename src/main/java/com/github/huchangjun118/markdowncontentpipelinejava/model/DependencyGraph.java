package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete dependency graph resolved from all source documents.
 *
 * @param includeEdges   doc -> docs it includes (transclusion, content-affecting)
 * @param referenceEdges doc -> docs it links to (navigation, existence-checked)
 */
public record DependencyGraph(
        Map<DocId, Set<DocId>> includeEdges,
        Map<DocId, Set<DocId>> referenceEdges,
        Set<DocId> nodes) {

    /**
     * Downstream docs that directly or transitively depend on the given doc,
     * via either include or reference edges. The changed doc itself is not included.
     */
    public Set<DocId> affectedDownstream(DocId changed) {
        Map<DocId, Set<DocId>> reverse = new HashMap<>();
        for (DocId n : nodes) {
            reverse.put(n, new HashSet<>());
        }
        includeEdges.forEach((from, tos) -> tos.forEach(to -> reverse.get(to).add(from)));
        referenceEdges.forEach((from, tos) -> tos.forEach(to -> reverse.get(to).add(from)));

        Set<DocId> affected = new HashSet<>();
        Deque<DocId> stack = new ArrayDeque<>();
        stack.push(changed);
        while (!stack.isEmpty()) {
            DocId cur = stack.pop();
            for (DocId down : reverse.getOrDefault(cur, Set.of())) {
                if (affected.add(down)) {
                    stack.push(down);
                }
            }
        }
        return Set.copyOf(affected);
    }

    /**
     * Transitive upstream include dependencies of the given doc
     * (docs whose content feeds this product).
     */
    public Set<DocId> transitiveIncludes(DocId doc) {
        Set<DocId> result = new HashSet<>();
        Deque<DocId> stack = new ArrayDeque<>();
        stack.push(doc);
        while (!stack.isEmpty()) {
            DocId cur = stack.pop();
            for (DocId up : includeEdges.getOrDefault(cur, Set.of())) {
                if (result.add(up)) {
                    stack.push(up);
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Docs that directly or transitively include the given doc. A content
     * change of the given doc invalidates exactly these products (plus its
     * own), because included content is inlined into the referrer's product.
     */
    public Set<DocId> includeAffectedUpstream(DocId changed) {
        Map<DocId, Set<DocId>> reverseIncludes = new HashMap<>();
        for (DocId n : nodes) {
            reverseIncludes.put(n, new HashSet<>());
        }
        includeEdges.forEach((from, tos) -> tos.forEach(to -> reverseIncludes.get(to).add(from)));

        Set<DocId> affected = new HashSet<>();
        Deque<DocId> stack = new ArrayDeque<>();
        stack.push(changed);
        while (!stack.isEmpty()) {
            DocId cur = stack.pop();
            for (DocId up : reverseIncludes.getOrDefault(cur, Set.of())) {
                if (affected.add(up)) {
                    stack.push(up);
                }
            }
        }
        return Set.copyOf(affected);
    }

    /**
     * Docs that directly link to the given doc (one hop, reverse reference
     * edges). Only link <em>existence</em> changes (add/delete/rename of the
     * target) affect these referrers' rendered output; a content change of the
     * target does not, so this is intentionally not transitive.
     */
    public Set<DocId> directReferrers(DocId target) {
        Set<DocId> referrers = new HashSet<>();
        referenceEdges.forEach((from, tos) -> {
            if (tos.contains(target)) {
                referrers.add(from);
            }
        });
        return Set.copyOf(referrers);
    }
}
