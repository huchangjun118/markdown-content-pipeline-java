package com.github.huchangjun118.markdowncontentpipelinejava.dep;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves raw references between documents into a complete dependency graph,
 * detects include cycles and missing references.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>An include target starting with {@code /} is resolved against the source
 *       root; otherwise relative to the including document's directory.</li>
 *   <li>Include targets without a {@code .md} suffix get one appended.</li>
 *   <li>Internal links are resolved relative to the document; anchors/queries
 *       are ignored.</li>
 * </ul>
 */
@Component
public class DependencyGraphBuilder {

    /** Resolution recorded for an include/reference target that does not exist. */
    public static final String UNRESOLVED = "\u0000unresolved\u0000";

    private final PipelineProperties properties;

    public DependencyGraphBuilder(PipelineProperties properties) {
        this.properties = properties;
    }

    public record GraphBuildResult(DependencyGraph graph,
                                   Map<DocId, String> pathByDocId,
                                   Map<String, DocId> docIdByPath,
                                   /** doc id -> raw include target -> resolved doc id, or {@link #UNRESOLVED} */
                                   Map<DocId, Map<String, String>> includeResolutions,
                                   /** doc id -> raw reference target -> resolved doc id, or {@link #UNRESOLVED} */
                                   Map<DocId, Map<String, String>> referenceResolutions,
                                   List<BuildError> errors,
                                   List<BuildError> warnings) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    public GraphBuildResult build(List<ParsedDocument> documents) {
        Map<String, DocId> docIdByPath = new LinkedHashMap<>();
        Map<DocId, String> pathByDocId = new LinkedHashMap<>();
        List<BuildError> errors = new ArrayList<>();
        List<BuildError> warnings = new ArrayList<>();

        // Duplicate doc id detection
        Map<DocId, String> firstPathById = new HashMap<>();
        for (ParsedDocument doc : documents) {
            DocId id = doc.id();
            if (firstPathById.containsKey(id)) {
                errors.add(BuildError.error(doc.sourcePath(), 0, BuildError.ErrorType.DUPLICATE_DOC_ID,
                        "Documents '%s' and '%s' both resolve to the same document id '%s'"
                                .formatted(firstPathById.get(id), doc.sourcePath(), id.value())));
            } else {
                firstPathById.put(id, doc.sourcePath());
            }
            docIdByPath.put(doc.sourcePath(), id);
            pathByDocId.put(id, doc.sourcePath());
        }

        Map<DocId, Set<DocId>> includeEdges = new LinkedHashMap<>();
        Map<DocId, Set<DocId>> referenceEdges = new LinkedHashMap<>();
        Map<DocId, Map<DocId, Integer>> includeLines = new HashMap<>();
        Map<DocId, Map<String, String>> includeResolutions = new LinkedHashMap<>();
        Map<DocId, Map<String, String>> referenceResolutions = new LinkedHashMap<>();
        for (ParsedDocument doc : documents) {
            includeEdges.put(doc.id(), new HashSet<>());
            referenceEdges.put(doc.id(), new HashSet<>());
            includeLines.put(doc.id(), new HashMap<>());
            includeResolutions.put(doc.id(), new TreeMap<>());
            referenceResolutions.put(doc.id(), new TreeMap<>());
        }

        for (ParsedDocument doc : documents) {
            String dir = parentDir(doc.sourcePath());

            for (ParsedDocument.RawRef ref : doc.includes()) {
                String resolved = resolveInclude(ref.target(), dir);
                DocId targetId = docIdByPath.get(resolved);
                if (targetId == null) {
                    includeResolutions.get(doc.id()).put(ref.target(), UNRESOLVED);
                    errors.add(BuildError.error(doc.sourcePath(), ref.line(),
                            BuildError.ErrorType.MISSING_INCLUDE,
                            "Included document '%s' (resolved to '%s') does not exist"
                                    .formatted(ref.target(), resolved)));
                    continue;
                }
                includeResolutions.get(doc.id()).put(ref.target(), targetId.value());
                if (targetId.equals(doc.id())) {
                    errors.add(BuildError.error(doc.sourcePath(), ref.line(),
                            BuildError.ErrorType.INCLUDE_CYCLE,
                            "Document '%s' includes itself".formatted(doc.sourcePath())));
                    continue;
                }
                includeEdges.get(doc.id()).add(targetId);
                includeLines.get(doc.id()).put(targetId, ref.line());
            }

            for (ParsedDocument.RawRef ref : doc.references()) {
                String resolved = resolveReference(ref.target(), dir);
                DocId targetId = docIdByPath.get(resolved);
                if (targetId == null) {
                    referenceResolutions.get(doc.id()).put(ref.target(), UNRESOLVED);
                    BuildError be = BuildError.error(doc.sourcePath(), ref.line(),
                            BuildError.ErrorType.MISSING_REFERENCE,
                            "Referenced document '%s' (resolved to '%s') does not exist"
                                    .formatted(ref.target(), resolved));
                    if (properties.getMissingRefPolicy() == PipelineProperties.MissingRefPolicy.WARN) {
                        warnings.add(be);
                    } else {
                        errors.add(be);
                    }
                    continue;
                }
                referenceResolutions.get(doc.id()).put(ref.target(), targetId.value());
                referenceEdges.get(doc.id()).add(targetId);
            }
        }

        // Include cycle detection (DFS over include edges)
        detectIncludeCycles(documents, includeEdges, includeLines, errors);

        return new GraphBuildResult(
                new DependencyGraph(includeEdges, referenceEdges, Set.copyOf(includeEdges.keySet())),
                pathByDocId, docIdByPath,
                deepUnmodifiable(includeResolutions), deepUnmodifiable(referenceResolutions),
                errors, warnings);
    }

    private static Map<DocId, Map<String, String>> deepUnmodifiable(Map<DocId, Map<String, String>> in) {
        Map<DocId, Map<String, String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return Map.copyOf(out);
    }

    private void detectIncludeCycles(List<ParsedDocument> documents,
                                     Map<DocId, Set<DocId>> includeEdges,
                                     Map<DocId, Map<DocId, Integer>> includeLines,
                                     List<BuildError> errors) {
        Set<DocId> visited = new HashSet<>();
        Set<DocId> onStack = new HashSet<>();
        List<DocId> stack = new ArrayList<>();

        for (ParsedDocument doc : documents) {
            if (!visited.contains(doc.id())) {
                dfs(doc.id(), includeEdges, includeLines, visited, onStack, stack, errors);
            }
        }
    }

    private void dfs(DocId node, Map<DocId, Set<DocId>> edges,
                     Map<DocId, Map<DocId, Integer>> lines,
                     Set<DocId> visited, Set<DocId> onStack, List<DocId> stack,
                     List<BuildError> errors) {
        visited.add(node);
        onStack.add(node);
        stack.add(node);
        for (DocId next : edges.getOrDefault(node, Set.of())) {
            if (!visited.contains(next)) {
                dfs(next, edges, lines, visited, onStack, stack, errors);
            } else if (onStack.contains(next)) {
                int idx = stack.indexOf(next);
                List<DocId> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
                cycle.add(next);
                int line = lines.getOrDefault(node, Map.of()).getOrDefault(next, 0);
                errors.add(BuildError.error(node.value(), line, BuildError.ErrorType.INCLUDE_CYCLE,
                        "Include cycle detected: " + cycle.stream().map(DocId::value).reduce((a, b) -> a + " -> " + b).orElse("")));
            }
        }
        stack.remove(stack.size() - 1);
        onStack.remove(node);
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    public static String resolveInclude(String target, String dir) {
        String t = target.trim();
        if (t.startsWith("/")) {
            t = t.substring(1);
        } else {
            t = (dir.isEmpty() ? t : dir + "/" + t);
        }
        if (!t.endsWith(".md")) {
            t = t + ".md";
        }
        return normalize(t);
    }

    public static String resolveReference(String target, String dir) {
        String t = target.trim();
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);
        int query = t.indexOf('?');
        if (query >= 0) t = t.substring(0, query);
        t = t.trim();
        if (!t.endsWith(".md")) t = t + ".md";
        t = (dir.isEmpty() ? t : dir + "/" + t);
        return normalize(t);
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(p);
        }
        return String.join("/", out);
    }
}
