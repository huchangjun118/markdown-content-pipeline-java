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
import java.util.HashSet;
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
 * <p>Incremental builds rebuild only the changed documents and the documents
 * whose rendered output depends on them (include upstreams, and referrers whose
 * links start or stop resolving); unaffected products keep their identifiers
 * and content hashes. The incremental result is identical to a full build
 * because every product identifier is computed from all of the product's
 * inputs - own source, link resolution, and (transitively) included products -
 * and rendering is deterministic.
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

            List<ParsedDocument> documents;
            DependencyGraphBuilder.GraphBuildResult graphResult;
            try {
                documents = parseAll(files);
                graphResult = graphBuilder.build(documents);
            } catch (BuildFailureException e) {
                log.warn("Full build failed: {} errors", e.errors.size());
                return BuildReport.failure(e.errors, e.warnings);
            }
            if (graphResult.hasErrors()) {
                log.warn("Full build failed: {} errors", graphResult.errors().size());
                return BuildReport.failure(graphResult.errors(), graphResult.warnings());
            }

            BuildResult result;
            try {
                result = buildAll(documents, graphResult, Set.of(), BuildVersion.VersionType.FULL, null);
            } catch (BuildFailureException e) {
                log.warn("Full build failed during rendering: {} errors", e.errors.size());
                return BuildReport.failure(e.errors, e.warnings);
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

            List<ParsedDocument> documents;
            DependencyGraphBuilder.GraphBuildResult graphResult;
            try {
                documents = parseAll(files);
                graphResult = graphBuilder.build(documents);
            } catch (BuildFailureException e) {
                log.warn("Incremental build failed: {} errors; published version {} remains current",
                        e.errors.size(), previous.versionId());
                return BuildReport.failure(e.errors, e.warnings);
            }
            if (graphResult.hasErrors()) {
                log.warn("Incremental build failed: {} errors; published version {} remains current",
                        graphResult.errors().size(), previous.versionId());
                return BuildReport.failure(graphResult.errors(), graphResult.warnings());
            }

            // Determine affected documents precisely:
            // - ADDED/MODIFIED: the doc itself plus everything that transitively
            //   includes it (its content feeds those products).
            // - ADDED: also docs whose previously-missing links now resolve
            //   (one reference hop; their rendered hrefs change), plus their
            //   include upstreams.
            // - DELETED: docs whose links resolved to the deleted path (found by
            //   re-resolving every reference against the deleted paths, so this
            //   works even after a restart), plus their include upstreams.
            //   Docs that included the deleted file fail the build above with
            //   MISSING_INCLUDE before reaching this point.
            DependencyGraph graph = graphResult.graph();
            Set<String> deletedPaths = new HashSet<>();
            for (SourceChange change : changes) {
                if (change.type() == SourceChange.Type.DELETED) {
                    deletedPaths.add(change.relativePath());
                }
            }

            Set<DocId> affected = new HashSet<>();
            for (SourceChange change : changes) {
                switch (change.type()) {
                    case ADDED -> {
                        affected.add(change.docId());
                        affected.addAll(graph.includeAffectedUpstream(change.docId()));
                        for (DocId referrer : graph.directReferrers(change.docId())) {
                            affected.add(referrer);
                            affected.addAll(graph.includeAffectedUpstream(referrer));
                        }
                    }
                    case MODIFIED -> {
                        affected.add(change.docId());
                        affected.addAll(graph.includeAffectedUpstream(change.docId()));
                    }
                    case DELETED -> cache.evictDoc(change.docId());
                }
            }
            if (!deletedPaths.isEmpty()) {
                for (DocId referrer : referrersOfPaths(documents, deletedPaths)) {
                    affected.add(referrer);
                    affected.addAll(graph.includeAffectedUpstream(referrer));
                }
            }
            affected.removeAll(idsOf(changes, SourceChange.Type.DELETED));

            BuildResult result;
            try {
                result = buildAll(documents, graphResult, affected,
                        BuildVersion.VersionType.INCREMENTAL, previous);
            } catch (BuildFailureException e) {
                log.warn("Incremental build failed during rendering: {} errors; published version {} remains current",
                        e.errors.size(), previous.versionId());
                return BuildReport.failure(e.errors, e.warnings);
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
        List<BuildError> errors = new ArrayList<>();
        for (SourceFile f : files) {
            try {
                documents.add(parser.parse(f.relativePath(), f.content()));
            } catch (RuntimeException e) {
                // A single unparseable document must not crash the build or
                // corrupt the published version: report it locatably instead.
                errors.add(BuildError.error(f.relativePath(), 0, BuildError.ErrorType.PARSE_ERROR,
                        "Failed to parse '%s': %s".formatted(f.relativePath(), e.getMessage())));
            }
        }
        if (!errors.isEmpty()) {
            throw new BuildFailureException(errors, List.of());
        }
        return documents;
    }

    /**
     * Finds every document that has an internal link resolving to one of the
     * given (deleted) source paths. Resolution is recomputed from the current
     * sources, so this is correct even with no in-memory state from previous
     * builds (e.g. right after a restart).
     */
    private Set<DocId> referrersOfPaths(List<ParsedDocument> documents, Set<String> paths) {
        Set<DocId> referrers = new HashSet<>();
        for (ParsedDocument doc : documents) {
            String dir = parentDir(doc.sourcePath());
            for (ParsedDocument.RawRef ref : doc.references()) {
                String resolved = DependencyGraphBuilder.resolveReference(ref.target(), dir);
                if (paths.contains(resolved)) {
                    referrers.add(doc.id());
                    break;
                }
            }
        }
        return referrers;
    }

    private static Set<DocId> idsOf(List<SourceChange> changes, SourceChange.Type type) {
        Set<DocId> ids = new HashSet<>();
        for (SourceChange change : changes) {
            if (change.type() == type) {
                ids.add(change.docId());
            }
        }
        return ids;
    }

    /**
     * Builds all documents in deterministic topological (include-first) order.
     *
     * @param forcedRebuild docs that must be rebuilt even if unchanged (incremental affected set);
     *                      empty for full builds (everything is built or reused from cache).
     */
    private BuildResult buildAll(List<ParsedDocument> documents,
                                 DependencyGraphBuilder.GraphBuildResult graphResult,
                                 Set<DocId> forcedRebuild,
                                 BuildVersion.VersionType type,
                                 BuildVersion previous) {
        DependencyGraph graph = graphResult.graph();
        Map<DocId, ParsedDocument> docById = new LinkedHashMap<>();
        for (ParsedDocument doc : documents) {
            docById.put(doc.id(), doc);
        }

        // Deterministic topological order over include edges (Kahn's algorithm
        // with a priority queue so ties are broken by doc id).
        List<DocId> order = topologicalOrder(graph);

        Map<DocId, ContentProduct> products = new LinkedHashMap<>();
        Map<DocId, String> hashes = new LinkedHashMap<>();
        List<DocId> built = new ArrayList<>();
        List<DocId> reused = new ArrayList<>();
        List<DocId> affected = new ArrayList<>();
        Map<DocId, String> manifest = new LinkedHashMap<>();

        for (DocId id : order) {
            ParsedDocument doc = docById.get(id);
            Set<DocId> transitiveIncludes = graph.transitiveIncludes(id);

            // The product identifier covers every input of the rendered output:
            // own identity and source, link resolution, and (transitively, via
            // their own hashes) all included products.
            Map<String, DocId> linkResolution = new HashMap<>();
            for (ParsedDocument.RawRef ref : doc.references()) {
                linkResolution.put(ref.target(), resolveLinkTarget(graphResult, doc, ref.target()));
            }
            Map<DocId, String> directIncludeHashes = new HashMap<>();
            for (DocId dep : graph.includeEdges().get(id)) {
                directIncludeHashes.put(dep, hashes.get(dep));
            }
            String contentHash = hasher.hashProduct(doc, linkResolution, directIncludeHashes);

            boolean mustRebuild = forcedRebuild.contains(id);
            ContentProduct product = null;

            if (!mustRebuild) {
                // 1) build cache (content-addressed)
                product = cache.get(id, contentHash).orElse(null);
                // 2) previously published product (incremental reuse)
                if (product == null && previous != null) {
                    String prevHash = previous.manifest().get(id);
                    if (prevHash != null && prevHash.equals(contentHash)) {
                        product = versionStore.productByHash(contentHash).orElse(null);
                    }
                }
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
                    // A single broken document must not corrupt the build or the
                    // published version: fail the build with a locatable error.
                    throw new BuildFailureException(List.of(BuildError.error(
                            doc.sourcePath(), 0, BuildError.ErrorType.RENDER_ERROR,
                            "Failed to render '%s': %s".formatted(doc.sourcePath(), e.getMessage()))),
                            List.of());
                }
                cache.put(id, contentHash, product);
                built.add(id);
                if (mustRebuild) {
                    affected.add(id);
                }
            } else {
                reused.add(id);
            }

            products.put(id, product);
            hashes.put(id, contentHash);
            manifest.put(id, contentHash);
        }

        // In incremental mode, carry over unchanged products from the previous
        // version that are not in the current graph (should not happen for
        // non-deleted docs, but keeps the manifest complete).
        BuildVersion published = new BuildVersion(
                0L, type,
                previous == null ? null : previous.versionId(),
                null,
                manifest,
                currentFileHashes(documents),
                hasher.hashSourceTree(currentFileHashes(documents)),
                0L, BuildVersion.State.ACTIVE);

        return new BuildResult(products, published, built, reused, affected);
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
        String resolved = DependencyGraphBuilder.resolveReference(rawTarget, dir);
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

    /** Carries locatable build errors out of the build pipeline stages. */
    private static final class BuildFailureException extends RuntimeException {
        private final List<BuildError> errors;
        private final List<BuildError> warnings;

        private BuildFailureException(List<BuildError> errors, List<BuildError> warnings) {
            super(errors.toString());
            this.errors = errors;
            this.warnings = warnings;
        }
    }

    private record BuildResult(Map<DocId, ContentProduct> products, BuildVersion published,
                               List<DocId> built, List<DocId> reused, List<DocId> affected) {
    }
}
