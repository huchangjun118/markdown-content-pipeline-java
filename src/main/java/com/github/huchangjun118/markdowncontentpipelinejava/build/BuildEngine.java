package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.dep.DependencyGraphBuilder;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceFile;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceChange;
import com.github.huchangjun118.markdowncontentpipelinejava.parse.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import com.github.huchangjun118.markdowncontentpipelinejava.watch.ChangeScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates full and incremental builds.
 *
 * <p>Builds are serialized by a build lock; reads never take it, so a rebuild
 * does not block reads and reads never observe a half-built state (versions are
 * published atomically).
 *
 * <p>Incremental builds rebuild only the changed documents and their directly or
 * transitively affected downstream documents; unaffected products keep their
 * identifiers and content hashes. The incremental result is identical to a full
 * build because every product hash is computed from its inputs and the same
 * deterministic rendering is used.
 */
@Component
public class BuildEngine {

    private static final Logger log = LoggerFactory.getLogger(BuildEngine.class);

    private final ChangeScanner scanner;
    private final MarkdownParser parser;
    private final DependencyGraphBuilder graphBuilder;
    private final ProductHasher hasher;
    private final ContentBuilder contentBuilder;
    private final BuildCache cache;
    private final VersionStore versionStore;

    private final ReentrantLock buildLock = new ReentrantLock();

    public BuildEngine(ChangeScanner scanner, MarkdownParser parser,
                       DependencyGraphBuilder graphBuilder, ProductHasher hasher,
                       ContentBuilder contentBuilder, BuildCache cache,
                       VersionStore versionStore) {
        this.scanner = scanner;
        this.parser = parser;
        this.graphBuilder = graphBuilder;
        this.hasher = hasher;
        this.contentBuilder = contentBuilder;
        this.cache = cache;
        this.versionStore = versionStore;
    }

    public BuildReport fullBuild() {
        buildLock.lock();
        try {
            log.info("Full build started");
            List<SourceFile> files;
            try {
                files = scanner.scan();
            } catch (IOException e) {
                return BuildReport.failure(List.of(
                        BuildError.error("", 0, BuildError.ErrorType.PARSE_ERROR, "Failed to scan source directory: " + e.getMessage())),
                        List.of());
            }

            List<ParsedDocument> documents = parseAll(files);
            DependencyGraphBuilder.GraphBuildResult graphResult = graphBuilder.build(documents);
            if (graphResult.hasErrors()) {
                log.warn("Full build failed: {} errors", graphResult.errors().size());
                return BuildReport.failure(graphResult.errors(), graphResult.warnings());
            }

            BuildResult result = buildAll(documents, graphResult, Map.of(), BuildVersion.VersionType.FULL, null);
            if (!result.errors().isEmpty()) {
                return BuildReport.failure(result.errors(), graphResult.warnings());
            }
            BuildVersion published = publish(result, BuildVersion.VersionType.FULL, null);
            log.info("Full build finished: {} built, {} reused", result.built.size(), result.reused.size());
            return new BuildReport(true, published.versionId(), List.of(), graphResult.warnings(),
                    result.built, result.reused, List.of());
        } finally {
            buildLock.unlock();
        }
    }

    public BuildReport incrementalBuild() {
        buildLock.lock();
        try {
            if (!versionStore.hasCurrent()) {
                log.info("No published version yet; falling back to full build");
                return fullBuild();
            }
            BuildVersion previous = versionStore.current();

            List<SourceFile> files;
            try {
                files = scanner.scan();
            } catch (IOException e) {
                return BuildReport.failure(List.of(
                        BuildError.error("", 0, BuildError.ErrorType.PARSE_ERROR, "Failed to scan source directory: " + e.getMessage())),
                        List.of());
            }

            Map<String, String> currentSnapshot = scanner.snapshot(files);
            List<SourceChange> changes = scanner.detectChanges(previous.fileHashes(), currentSnapshot);
            if (changes.isEmpty()) {
                log.info("No source changes; incremental build is a no-op");
                return new BuildReport(true, previous.versionId(), List.of(), List.of(),
                        List.of(), List.of(), List.of());
            }

            List<ParsedDocument> documents = parseAll(files);
            DependencyGraphBuilder.GraphBuildResult graphResult = graphBuilder.build(documents);
            if (graphResult.hasErrors()) {
                log.warn("Incremental build failed: {} errors; published version {} remains current",
                        graphResult.errors().size(), previous.versionId());
                return BuildReport.failure(graphResult.errors(), graphResult.warnings());
            }

            // Products of deleted documents can never be referenced again by
            // their doc id; drop them from the build cache promptly.
            for (SourceChange change : changes) {
                if (change.type() == SourceChange.Type.DELETED) {
                    cache.evictDoc(change.docId());
                }
            }

            // The affected set is derived purely from product fingerprints:
            // a document is affected iff its new hash differs from the hash the
            // previous committed version records for it (or it is new). Because
            // the fingerprint covers reference/include resolution in the whole
            // render closure, deleting, renaming or re-adding a referenced
            // document changes every referencing document's hash even when its
            // own bytes are untouched. This does not rely on any in-memory
            // graph, so it stays correct after restarts and rollbacks.
            BuildResult result = buildAll(documents, graphResult, previous.manifest(),
                    BuildVersion.VersionType.INCREMENTAL, previous);
            if (!result.errors().isEmpty()) {
                log.warn("Incremental render failed: {} error(s); published version {} remains current",
                        result.errors().size(), previous.versionId());
                return BuildReport.failure(result.errors(), graphResult.warnings());
            }
            BuildVersion published = publish(result, BuildVersion.VersionType.INCREMENTAL, previous);
            log.info("Incremental build finished: {} built, {} reused, {} affected",
                    result.built.size(), result.reused.size(), result.affected.size());
            return new BuildReport(true, published.versionId(), List.of(), graphResult.warnings(),
                    result.built, result.reused, List.copyOf(result.affected));
        } finally {
            buildLock.unlock();
        }
    }

    private List<ParsedDocument> parseAll(List<SourceFile> files) {
        List<ParsedDocument> documents = new ArrayList<>();
        for (SourceFile f : files) {
            documents.add(parser.parse(f.relativePath(), f.content()));
        }
        return documents;
    }

    /**
     * Builds all documents in deterministic topological (include-first) order.
     *
     * @param previousManifest manifest of the version this build extends
     *                         (empty for full builds). A document is rebuilt
     *                         only when its newly computed fingerprint differs
     *                         from the one recorded there; otherwise the exact
     *                         previously published product is reused.
     */
    private BuildResult buildAll(List<ParsedDocument> documents,
                                 DependencyGraphBuilder.GraphBuildResult graphResult,
                                 Map<DocId, String> previousManifest,
                                 BuildVersion.VersionType type,
                                 BuildVersion previous) {
        DependencyGraph graph = graphResult.graph();
        ProductHasher.ResolutionTables resolutions = new ProductHasher.ResolutionTables(
                graphResult.includeResolutions(), graphResult.referenceResolutions());

        Map<DocId, ParsedDocument> docById = new LinkedHashMap<>();
        for (ParsedDocument doc : documents) {
            docById.put(doc.id(), doc);
        }

        // Deterministic topological order over include edges (Kahn's algorithm
        // with a priority queue so ties are broken by doc id).
        List<DocId> order = topologicalOrder(graph);

        Map<DocId, ContentProduct> products = new LinkedHashMap<>();
        List<DocId> built = new ArrayList<>();
        List<DocId> reused = new ArrayList<>();
        List<DocId> affected = new ArrayList<>();
        List<BuildError> errors = new ArrayList<>();
        Map<DocId, String> manifest = new LinkedHashMap<>();

        for (DocId id : order) {
            ParsedDocument doc = docById.get(id);
            Set<DocId> transitiveIncludes = graph.transitiveIncludes(id);
            Map<DocId, ParsedDocument> includedSources = new HashMap<>();
            for (DocId dep : transitiveIncludes) {
                includedSources.put(dep, docById.get(dep));
            }
            List<DocId> closure = ProductHasher.closure(doc, includedSources);
            String contentHash = hasher.hashProduct(doc, includedSources, closure, resolutions);

            String previousHash = previousManifest.get(id);
            boolean unchanged = contentHash.equals(previousHash);

            ContentProduct product = null;
            if (unchanged && previous != null) {
                // Fingerprint identical to the committed version: reuse the
                // exact published product so identifier and content stay equal.
                product = versionStore.productByHash(contentHash).orElse(null);
            }
            if (product == null) {
                product = cache.get(id, contentHash).orElse(null);
            }

            if (product == null) {
                Map<DocId, ContentProduct> directIncludes = new HashMap<>();
                for (DocId dep : graph.includeEdges().get(id)) {
                    directIncludes.put(dep, products.get(dep));
                }
                try {
                    product = contentBuilder.build(doc, directIncludes,
                            target -> resolveIncludeTarget(graphResult, doc, target),
                            target -> resolveLinkTarget(graphResult, doc, target),
                            contentHash, transitiveIncludes);
                } catch (RuntimeException e) {
                    errors.add(BuildError.error(doc.sourcePath(), 0, BuildError.ErrorType.RENDER_ERROR,
                            "Failed to render document '%s': %s".formatted(doc.sourcePath(), e.getMessage())));
                    break;
                }
                cache.put(id, contentHash, product);
                built.add(id);
            } else {
                reused.add(id);
            }

            products.put(id, product);
            manifest.put(id, contentHash);
            if (previousHash == null || !previousHash.equals(contentHash)) {
                affected.add(id);
            }
        }

        BuildVersion published = new BuildVersion(
                0L, type,
                previous == null ? null : previous.versionId(),
                null,
                manifest,
                currentFileHashes(documents),
                hasher.hashSourceTree(currentFileHashes(documents)),
                0L, BuildVersion.State.ACTIVE);

        return new BuildResult(products, published, built, reused, affected, errors);
    }

    private Map<String, String> currentFileHashes(List<ParsedDocument> documents) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ParsedDocument doc : documents) {
            hashes.put(doc.sourcePath(), hashSource(doc));
        }
        return hashes;
    }

    private static String hashSource(ParsedDocument doc) {
        return ProductHasher.sha256(doc.rawContent());
    }

    private DocId resolveIncludeTarget(DependencyGraphBuilder.GraphBuildResult graphResult,
                                       ParsedDocument doc, String rawTarget) {
        String dir = parentDir(doc.sourcePath());
        String resolved = DependencyGraphBuilder.resolveInclude(rawTarget, dir);
        return graphResult.docIdByPath().get(resolved);
    }

    private DocId resolveLinkTarget(DependencyGraphBuilder.GraphBuildResult graphResult,
                                    ParsedDocument doc, String rawTarget) {
        String target = rawTarget.trim();
        if (target.startsWith("#")) return null;
        if (target.contains("://") || target.startsWith("mailto:")) return null;
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (!target.endsWith(".md")) return null;
        String dir = parentDir(doc.sourcePath());
        String resolved = DependencyGraphBuilder.resolveReference(target, dir);
        return graphResult.docIdByPath().get(resolved);
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private List<DocId> topologicalOrder(DependencyGraph graph) {
        Map<DocId, Integer> indegree = new HashMap<>();
        for (DocId n : graph.nodes()) {
            indegree.put(n, 0);
        }
        graph.includeEdges().forEach((from, tos) -> {
            for (DocId to : tos) {
                indegree.merge(from, 1, Integer::sum);
            }
        });
        // indegree[from] = number of docs it includes? No: for Kahn on edges
        // "from includes to", to must come first. Edge from -> to means from
        // depends on to. indegree of `from` = number of its includes.
        PriorityQueue<DocId> ready = new PriorityQueue<>();
        for (Map.Entry<DocId, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        List<DocId> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            DocId n = ready.poll();
            order.add(n);
            // n is included by its referrers; reduce their indegree
            for (DocId from : graph.nodes()) {
                if (graph.includeEdges().getOrDefault(from, Set.of()).contains(n)) {
                    int deg = indegree.merge(from, -1, Integer::sum);
                    if (deg == 0) ready.add(from);
                }
            }
        }
        if (order.size() != graph.nodes().size()) {
            // Should not happen: cycles are detected before building.
            throw new IllegalStateException("Include cycle detected during build ordering");
        }
        return order;
    }

    private BuildVersion publish(BuildResult result, BuildVersion.VersionType type, BuildVersion previous) {
        return versionStore.publish(result.published, result.products);
    }

    private record BuildResult(Map<DocId, ContentProduct> products, BuildVersion published,
                               List<DocId> built, List<DocId> reused, List<DocId> affected,
                               List<BuildError> errors) {
    }
}
