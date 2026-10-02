package com.github.huchangjun118.markdowncontentpipelinejava.engine;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ErrorKind;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.ArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Assembles full and incremental candidate builds from the source directory.
 *
 * <p>Determinism guarantees:
 * <ul>
 *   <li>Documents are always processed in one sorted topological order
 *       defined from the include graph (dependencies first, ties broken by
 *       id), so include expansion and error ordering never depend on thread
 *       scheduling or build order.</li>
 *   <li>An incremental build renders <em>exactly</em> the changed roots and
 *       their transitive downstream documents. Every unaffected artifact,
 *       including its {@code contentId}, is reused byte-for-byte, and the
 *       resulting candidate equals a full build of the same sources.</li>
 *   <li>Deletions and renames invalidate every cached artifact that embedded
 *       the removed id before anything is rendered.</li>
 * </ul>
 */
@Component
public class BuildEngine {

    private static final Logger log = LoggerFactory.getLogger(BuildEngine.class);

    private final SourceDirectoryScanner scanner;
    private final MarkdownParser parser;
    private final DependencyResolver resolver;
    private final ArtifactRenderer renderer;
    private final BuildCache cache;
    private final ContentPipelineProperties properties;

    private EngineState lastState = EngineState.empty();

    public BuildEngine(
            SourceDirectoryScanner scanner,
            MarkdownParser parser,
            DependencyResolver resolver,
            ArtifactRenderer renderer,
            BuildCache cache,
            ContentPipelineProperties properties) {
        this.scanner = scanner;
        this.parser = parser;
        this.resolver = resolver;
        this.renderer = renderer;
        this.cache = cache;
        this.properties = properties;
    }

    /** Full build: every existing document is rendered (cache may still short-circuit work). */
    public CandidateBuild fullBuild() {
        Map<String, SourceSnapshot> sources = scanSources();
        Map<String, ChangeType> changes = new LinkedHashMap<>();
        for (String id : sources.keySet()) {
            changes.put(id, lastState.sources().containsKey(id)
                    ? ChangeType.MODIFIED : ChangeType.ADDED);
        }
        for (String gone : lastState.sources().keySet()) {
            changes.putIfAbsent(gone, ChangeType.DELETED);
        }
        return assemble(sources, new LinkedHashSet<>(sources.keySet()), changes, BuildMode.FULL);
    }

    /**
     * Incremental build: only the given roots and their transitive downstream
     * documents are rendered; unaffected artifact identities are preserved.
     */
    public CandidateBuild incrementalBuild(Map<String, ChangeType> reportedChanges) {
        Map<String, SourceSnapshot> sources = scanSources();

        // Reconcile reported changes with the filesystem so callers cannot miss
        // a root: additional hash changes and deletions are detected here.
        Map<String, ChangeType> changes = new LinkedHashMap<>(reportedChanges);
        for (Map.Entry<String, SourceSnapshot> entry : sources.entrySet()) {
            SourceSnapshot previous = lastState.sources().get(entry.getKey());
            if (previous == null) {
                changes.putIfAbsent(entry.getKey(), ChangeType.ADDED);
            } else if (!previous.contentHash().equals(entry.getValue().contentHash())) {
                changes.putIfAbsent(entry.getKey(), ChangeType.MODIFIED);
            }
        }
        for (String gone : lastState.sources().keySet()) {
            if (!sources.containsKey(gone)) {
                changes.put(gone, ChangeType.DELETED);
            }
        }

        // Closure roots include deleted ids: they no longer render themselves
        // (they are absent from the parsed set / render order) but must still
        // propagate to their former referrers so dangling includes are rebuilt.
        Set<String> roots = new LinkedHashSet<>(changes.keySet());
        return assemble(sources, roots, changes, BuildMode.INCREMENTAL);
    }

    private Map<String, SourceSnapshot> scanSources() {
        return scanner.scan(Path.of(properties.getSourceDir()), properties.getSourceSuffix());
    }

    private CandidateBuild assemble(
            Map<String, SourceSnapshot> sources,
            Set<String> rootIds,
            Map<String, ChangeType> changes,
            BuildMode mode) {

        // 1. Deletion/rename invalidation must precede rendering.
        for (Map.Entry<String, ChangeType> entry : changes.entrySet()) {
            if (entry.getValue() == ChangeType.DELETED) {
                cache.invalidateAffected(entry.getKey());
            }
        }

        // 2. Parse all sources; parse failures are located, never fatal to build.
        Map<String, ParsedDocument> parsed = new LinkedHashMap<>();
        List<BuildError> errors = new ArrayList<>();
        Set<String> fatalDocuments = new LinkedHashSet<>();
        for (String id : sortedKeys(sources)) {
            try {
                parsed.put(id, parser.parse(sources.get(id)));
            } catch (RuntimeException e) {
                BuildError error = BuildError.fatal(id, ErrorKind.PARSE_ERROR, 0,
                        "parse failed: " + safeMessage(e));
                errors.add(error);
                fatalDocuments.add(id);
            }
        }

        // 3. Resolve the complete dependency graph.
        DependencyGraph graph = resolver.resolve(parsed);

        // 4. Affected documents: change roots plus every transitive referrer.
        //    Embedded include content always affects the referrer. A linked
        //    document's *content* is not embedded, only its existence enters
        //    the key, so link propagation is needed for ADDED/DELETED roots
        //    only; MODIFIED roots propagate along include edges alone.
        Set<String> affected = mode == BuildMode.FULL
                ? new LinkedHashSet<>(parsed.keySet())
                : downstreamClosure(rootIds, changes, parsed, lastState.parsed());

        // 5. Deterministic order: dependencies first, sorted ids on ties.
        List<String> order = topologicalOrder(parsed, graph);

        Map<String, BuildArtifact> artifacts = new LinkedHashMap<>();
        Map<String, String> cacheKeys = new LinkedHashMap<>();
        Map<String, String> keyMemo = new HashMap<>();
        int rebuilt = 0;
        int reused = 0;

        for (String id : order) {
            if (fatalDocuments.contains(id)) {
                continue;
            }
            if (!affected.contains(id)) {
                // Unaffected reuse is only valid while the document still exists
                // in the scanned source set; deleted ids are simply dropped.
                BuildArtifact previous = sources.containsKey(id)
                        ? lastState.artifacts().get(id) : null;
                if (previous != null) {
                    artifacts.put(id, previous);
                    String previousKey = lastState.cacheKeys().get(id);
                    if (previousKey != null) {
                        cacheKeys.put(id, previousKey);
                    }
                    reused++;
                    continue;
                }
            }

            String key = renderer.cacheKey(id, parsed, graph, keyMemo);
            BuildArtifact artifact = null;
            if (key != null) {
                artifact = cache.get(key).orElse(null);
            }
            if (artifact == null) {
                int before = errors.size();
                Optional<BuildArtifact> rendered = renderer.render(id, parsed, artifacts, graph, errors);
                if (rendered.isPresent()) {
                    artifact = rendered.get();
                    if (key != null) {
                        cache.put(key, artifact);
                    }
                } else {
                    for (int i = before; i < errors.size(); i++) {
                        if (errors.get(i).fatal()) {
                            fatalDocuments.add(errors.get(i).documentId());
                        }
                    }
                }
            }
            if (artifact != null) {
                artifacts.put(id, artifact);
                if (key != null) {
                    cacheKeys.put(id, key);
                }
            }
            rebuilt++;
        }

        sortErrors(errors);

        CandidateBuild candidate = new CandidateBuild(
                Map.copyOf(artifacts),
                List.copyOf(errors),
                Map.copyOf(sources),
                rebuilt,
                reused,
                (int) changes.size());
        lastState = new EngineState(
                Map.copyOf(sources),
                Map.copyOf(parsed),
                Map.copyOf(artifacts),
                Map.copyOf(cacheKeys));
        return candidate;
    }

    /**
     * Reverse closure over typed dependency edges.
     *
     * <p>The reverse adjacency is built from the union of the new parsed set
     * <em>and</em> the previous build's parsed set: a deleted document no
     * longer appears in the new graph, but its former referrers do, and they
     * must be rebuilt (the include/link they carry now dangles or was edited
     * in the same change).
     *
     * <p>Include edges embed content, so any change kind on the target affects
     * the referrer. Link edges do not embed content; they only contribute a
     * target-existence bit to the key, so a link edge is traversed solely for
     * ADDED/DELETED roots.
     */
    private Set<String> downstreamClosure(
            Set<String> roots,
            Map<String, ChangeType> changes,
            Map<String, ParsedDocument> parsed,
            Map<String, ParsedDocument> previousParsed) {

        Map<String, Set<String>> includeReferrers = new LinkedHashMap<>();
        Map<String, Set<String>> linkReferrers = new LinkedHashMap<>();
        collectReverseEdges(parsed, includeReferrers, linkReferrers, true);
        collectReverseEdges(previousParsed, includeReferrers, linkReferrers, false);

        Set<String> affected = new LinkedHashSet<>();
        Deque<String> includeWork = new ArrayDeque<>(roots);
        while (!includeWork.isEmpty()) {
            String id = includeWork.poll();
            if (!affected.add(id)) {
                continue;
            }
            Set<String> viaInclude = includeReferrers.get(id);
            if (viaInclude != null) {
                includeWork.addAll(viaInclude);
            }
        }
        // One hop over link edges, only for existence changes.
        for (String root : roots) {
            ChangeType type = changes.get(root);
            if (type == ChangeType.ADDED || type == ChangeType.DELETED) {
                Set<String> direct = linkReferrers.get(root);
                if (direct != null) {
                    affected.addAll(direct);
                }
            }
        }
        return affected;
    }

    private void collectReverseEdges(
            Map<String, ParsedDocument> documents,
            Map<String, Set<String>> includeReferrers,
            Map<String, Set<String>> linkReferrers,
            boolean ensureKeys) {
        for (ParsedDocument doc : documents.values()) {
            if (ensureKeys) {
                includeReferrers.computeIfAbsent(doc.id(), k -> new LinkedHashSet<>());
                linkReferrers.computeIfAbsent(doc.id(), k -> new LinkedHashSet<>());
            }
            for (String target : doc.includes()) {
                includeReferrers.computeIfAbsent(target, k -> new LinkedHashSet<>())
                        .add(doc.id());
            }
            for (String target : doc.links()) {
                linkReferrers.computeIfAbsent(target, k -> new LinkedHashSet<>())
                        .add(doc.id());
            }
        }
    }

    /**
     * Kahn topological sort on include edges (depends-on counts), sorted ids
     * on ties. Nodes trapped inside include cycles are appended last, sorted,
     * so they are rendered at a deterministic point and fail/break there.
     */
    private List<String> topologicalOrder(Map<String, ParsedDocument> parsed, DependencyGraph graph) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        for (String id : parsed.keySet()) {
            remaining.put(id, 0);
            dependents.put(id, new LinkedHashSet<>());
        }
        for (Map.Entry<String, List<String>> edge : graph.includeEdges().entrySet()) {
            String dependent = edge.getKey();
            for (String dependency : edge.getValue()) {
                if (parsed.containsKey(dependency)
                        && dependents.get(dependency).add(dependent)) {
                    remaining.merge(dependent, 1, Integer::sum);
                }
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : dependents.get(id)) {
                int left = remaining.get(dependent) - 1;
                remaining.put(dependent, left);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        for (String id : sortedKeys(parsed)) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    private static void sortErrors(List<BuildError> errors) {
        errors.sort(Comparator
                .comparing(BuildError::documentId)
                .thenComparing(e -> e.kind().name())
                .thenComparingInt(BuildError::line)
                .thenComparing(e -> e.message() == null ? "" : e.message()));
    }

    private static List<String> sortedKeys(Map<String, ?> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }

    private static String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
