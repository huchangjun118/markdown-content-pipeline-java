package com.github.huchangjun118.markdowncontentpipelinejava.graph;

import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.util.Hashing;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the complete dependency graph of a parsed source set.
 *
 * <p>Edges point from a document to everything it references. Only include
 * edges participate in cycle detection and topological expansion order; link
 * edges never create cycles that fail a build (linked-to documents are not
 * embedded).
 *
 * <p>Cycle detection computes strongly connected components with an iterative
 * Kosaraju pass, so deeply nested includes cannot overflow the JVM stack.
 * Every SCC with more than one member (or a self edge) is a cycle; it gets an
 * id derived from its sorted member list so the same cycle in two builds is
 * always reported and keyed identically.
 */
@Component
public class DependencyResolver {

    public DependencyGraph resolve(Map<String, ParsedDocument> parsed) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        Map<String, List<String>> includeEdges = new LinkedHashMap<>();
        Map<String, List<DependencyGraph.ReferenceLocation>> missing = new LinkedHashMap<>();

        for (ParsedDocument doc : parsed.values()) {
            Set<String> all = new LinkedHashSet<>();
            List<String> includes = new ArrayList<>();
            for (String target : doc.includes()) {
                includes.add(target);
                all.add(target);
                if (!parsed.containsKey(target)) {
                    missing.computeIfAbsent(target, k -> new ArrayList<>())
                            .add(new DependencyGraph.ReferenceLocation(
                                    doc.id(), doc.positions().getOrDefault(target, 0)));
                }
            }
            for (String target : doc.links()) {
                all.add(target);
                if (!parsed.containsKey(target)) {
                    missing.computeIfAbsent(target, k -> new ArrayList<>())
                            .add(new DependencyGraph.ReferenceLocation(
                                    doc.id(), doc.positions().getOrDefault(target, 0)));
                }
            }
            includeEdges.put(doc.id(), List.copyOf(includes));
            edges.put(doc.id(), List.copyOf(all));
        }

        List<Set<String>> components = stronglyConnectedComponents(parsed, includeEdges);
        List<String> cyclic = new ArrayList<>();
        Map<String, String> cyclicScc = new LinkedHashMap<>();
        for (Set<String> component : components) {
            boolean selfEdge = component.size() == 1
                    && includeEdges.getOrDefault(component.iterator().next(), List.of())
                            .contains(component.iterator().next());
            if (component.size() > 1 || selfEdge) {
                List<String> members = new ArrayList<>(component);
                java.util.Collections.sort(members);
                String sccId = "scc-" + Hashing.sha256(String.join("\n", members)).substring(0, 16);
                for (String member : members) {
                    cyclic.add(member);
                    cyclicScc.put(member, sccId);
                }
            }
        }
        java.util.Collections.sort(cyclic);

        return new DependencyGraph(
                orderByKey(edges),
                orderByKey(includeEdges),
                List.copyOf(cyclic),
                Map.copyOf(cyclicScc),
                sortMissing(missing));
    }

    /** Iterative Kosaraju: deterministic finish order is built from sorted nodes. */
    private List<Set<String>> stronglyConnectedComponents(
            Map<String, ParsedDocument> parsed,
            Map<String, List<String>> includeEdges) {

        List<String> nodes = new ArrayList<>(parsed.keySet());
        java.util.Collections.sort(nodes);
        Set<String> visited = new LinkedHashSet<>();
        List<String> finishOrder = new ArrayList<>();

        for (String root : nodes) {
            if (!visited.add(root)) {
                continue;
            }
            Deque<String> stack = new ArrayDeque<>();
            Deque<Integer> edgeIndex = new ArrayDeque<>();
            stack.push(root);
            edgeIndex.push(0);
            while (!stack.isEmpty()) {
                String node = stack.peek();
                List<String> neighbors = includeEdges.getOrDefault(node, List.of());
                int idx = edgeIndex.peek();
                if (idx < neighbors.size()) {
                    edgeIndex.pop();
                    edgeIndex.push(idx + 1);
                    String next = neighbors.get(idx);
                    if (parsed.containsKey(next) && visited.add(next)) {
                        stack.push(next);
                        edgeIndex.push(0);
                    }
                } else {
                    finishOrder.add(node);
                    stack.pop();
                    edgeIndex.pop();
                }
            }
        }

        // Reverse graph.
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        nodes.forEach(id -> reverse.put(id, new ArrayList<>()));
        for (String id : nodes) {
            for (String target : includeEdges.getOrDefault(id, List.of())) {
                if (parsed.containsKey(target)) {
                    reverse.get(target).add(id);
                }
            }
        }

        java.util.Collections.reverse(finishOrder);
        Set<String> assigned = new LinkedHashSet<>();
        List<Set<String>> components = new ArrayList<>();
        for (String root : finishOrder) {
            if (!assigned.add(root)) {
                continue;
            }
            Set<String> component = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(root);
            component.add(root);
            while (!stack.isEmpty()) {
                String node = stack.pop();
                for (String parent : reverse.getOrDefault(node, List.of())) {
                    if (assigned.add(parent)) {
                        component.add(parent);
                        stack.push(parent);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private static Map<String, List<String>> orderByKey(Map<String, List<String>> input) {
        // Values keep their document order; only map iteration order is pinned.
        Map<String, List<String>> out = new LinkedHashMap<>();
        input.keySet().stream().sorted().forEach(id -> out.put(id, input.get(id)));
        return out;
    }

    private static Map<String, List<DependencyGraph.ReferenceLocation>> sortMissing(
            Map<String, List<DependencyGraph.ReferenceLocation>> input) {
        Map<String, List<DependencyGraph.ReferenceLocation>> out = new LinkedHashMap<>();
        input.keySet().stream().sorted().forEach(id -> {
            List<DependencyGraph.ReferenceLocation> locs = new ArrayList<>(input.get(id));
            locs.sort((a, b) -> {
                int c = a.referrerId().compareTo(b.referrerId());
                return c != 0 ? c : Integer.compare(a.line(), b.line());
            });
            out.put(id, List.copyOf(locs));
        });
        return out;
    }
}
