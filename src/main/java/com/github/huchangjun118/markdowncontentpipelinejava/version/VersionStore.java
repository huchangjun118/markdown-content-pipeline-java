package com.github.huchangjun118.markdowncontentpipelinejava.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Immutable version store.
 *
 * <p>Reads always observe a single committed version (an atomic reference),
 * never a half-built state, and never block on builds. Publish and rollback
 * are serialized by a single lock so concurrent calls have a deterministic
 * total order.
 *
 * <p>Products are content-addressed and shared across versions; history is
 * bounded by the configured retention, after which old versions are archived
 * and their product files garbage-collected.
 */
@Component
public class VersionStore {

    private static final Logger log = LoggerFactory.getLogger(VersionStore.class);

    private final PipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final Path productsDir;
    private final Path versionsDir;
    private final Path currentFile;

    private final Map<Long, BuildVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, ContentProduct> products = new ConcurrentHashMap<>();
    private final AtomicReference<BuildVersion> current = new AtomicReference<>();
    private final AtomicLong versionIdGenerator = new AtomicLong(0);
    private final ReentrantLock publishLock = new ReentrantLock();

    public VersionStore(PipelineProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        Path out = Path.of(properties.getOutputDir());
        this.productsDir = out.resolve("products");
        this.versionsDir = out.resolve("versions");
        this.currentFile = out.resolve("current");
    }

    @PostConstruct
    public void load() {
        try {
            Files.createDirectories(productsDir);
            Files.createDirectories(versionsDir);
            loadProducts();
            loadVersions();
            if (Files.exists(currentFile)) {
                long currentId = Long.parseLong(Files.readString(currentFile).trim());
                BuildVersion v = versions.get(currentId);
                if (v != null) {
                    current.set(v);
                    versionIdGenerator.set(versions.keySet().stream().mapToLong(Long::longValue).max().orElse(0));
                }
            }
            log.info("Loaded {} versions, {} products; current version: {}",
                    versions.size(), products.size(),
                    current.get() == null ? "none" : current.get().versionId());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load version store from " + properties.getOutputDir(), e);
        }
    }

    private void loadProducts() throws IOException {
        try (var stream = Files.list(productsDir)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
                ContentProduct product = objectMapper.readValue(p.toFile(), ContentProduct.class);
                products.put(product.contentHash(), product);
            }
        }
    }

    private void loadVersions() throws IOException {
        try (var stream = Files.list(versionsDir)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".json")).toList()) {
                BuildVersion v = objectMapper.readValue(p.toFile(), BuildVersion.class);
                versions.put(v.versionId(), v);
            }
        }
    }

    /**
     * Publishes a new immutable version. The version id is assigned by the store.
     * Products are content-addressed and shared.
     */
    public BuildVersion publish(BuildVersion version, Map<DocId, ContentProduct> builtProducts) {
        publishLock.lock();
        try {
            long id = versionIdGenerator.incrementAndGet();
            BuildVersion published = new BuildVersion(
                    id, version.type(), version.parentVersionId(), version.sourceVersionId(),
                    version.manifest(), version.fileHashes(), version.sourceFingerprint(),
                    System.currentTimeMillis(), BuildVersion.State.ACTIVE);

            for (ContentProduct product : builtProducts.values()) {
                products.put(product.contentHash(), product);
                writeProduct(product);
            }
            writeVersion(published);
            versions.put(published.versionId(), published);
            current.set(published);
            writeCurrent(published.versionId());
            archiveOldVersions();
            gcUnreferencedProducts();
            log.info("Published version {} ({} documents)", published.versionId(), published.manifest().size());
            return published;
        } finally {
            publishLock.unlock();
        }
    }

    /**
     * Rolls back to a historical version. A new ROLLBACK version is created with
     * the same manifest, so reads return exactly the historical content while
     * history retains the rollback event.
     */
    public BuildVersion rollbackTo(long targetVersionId) {
        publishLock.lock();
        try {
            BuildVersion target = versions.get(targetVersionId);
            if (target == null) {
                throw new IllegalArgumentException("Version " + targetVersionId + " does not exist");
            }
            if (target.state() == BuildVersion.State.ARCHIVED) {
                throw new IllegalStateException("Version " + targetVersionId + " is archived; its products have been garbage-collected");
            }
            long id = versionIdGenerator.incrementAndGet();
            BuildVersion rollback = new BuildVersion(
                    id, BuildVersion.VersionType.ROLLBACK,
                    current.get() == null ? null : current.get().versionId(),
                    target.versionId(),
                    new LinkedHashMap<>(target.manifest()),
                    new LinkedHashMap<>(target.fileHashes()),
                    target.sourceFingerprint(),
                    System.currentTimeMillis(), BuildVersion.State.ACTIVE);
            writeVersion(rollback);
            versions.put(rollback.versionId(), rollback);
            current.set(rollback);
            writeCurrent(rollback.versionId());
            log.info("Rolled back to version {} via new version {}", targetVersionId, rollback.versionId());
            return rollback;
        } finally {
            publishLock.unlock();
        }
    }

    public BuildVersion current() {
        BuildVersion v = current.get();
        if (v == null) {
            throw new IllegalStateException("No version has been published yet");
        }
        return v;
    }

    public boolean hasCurrent() {
        return current.get() != null;
    }

    public Optional<BuildVersion> version(long versionId) {
        return Optional.ofNullable(versions.get(versionId));
    }

    public List<BuildVersion> history() {
        return versions.values().stream()
                .sorted(Comparator.comparingLong(BuildVersion::versionId))
                .toList();
    }

    /** Reads the product for a document from the current immutable version. */
    public Optional<ContentProduct> product(DocId docId) {
        BuildVersion v = current.get();
        if (v == null) return Optional.empty();
        String hash = v.manifest().get(docId);
        if (hash == null) return Optional.empty();
        return Optional.ofNullable(products.get(hash));
    }

    /** Product by content hash (used by the engine for reuse checks). */
    public Optional<ContentProduct> productByHash(String hash) {
        return Optional.ofNullable(products.get(hash));
    }

    /**
     * Garbage-collects products not referenced by any retained (ACTIVE) version.
     * This promptly reclaims products of deleted/renamed documents while keeping
     * every rollback target readable.
     */
    private void gcUnreferencedProducts() {
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (BuildVersion v : history()) {
            if (v.state() == BuildVersion.State.ACTIVE) {
                referenced.addAll(v.manifest().values());
            }
        }
        products.entrySet().removeIf(e -> {
            if (referenced.contains(e.getKey())) return false;
            try {
                Files.deleteIfExists(productsDir.resolve(e.getKey() + ".json"));
            } catch (IOException ex) {
                log.warn("Failed to delete orphan product file {}", e.getKey(), ex);
            }
            return true;
        });
    }

    private void archiveOldVersions() {
        List<BuildVersion> all = history();
        int retention = properties.getVersionRetention();
        if (all.size() <= retention) return;

        // Hashes still referenced by retained versions
        List<BuildVersion> retained = all.subList(Math.max(0, all.size() - retention), all.size());
        java.util.Set<String> retainedHashes = new java.util.HashSet<>();
        for (BuildVersion v : retained) {
            retainedHashes.addAll(v.manifest().values());
        }

        for (BuildVersion old : all) {
            if (retained.contains(old)) continue;
            if (old.state() == BuildVersion.State.ARCHIVED) continue;
            BuildVersion archived = new BuildVersion(
                    old.versionId(), old.type(), old.parentVersionId(), old.sourceVersionId(),
                    old.manifest(), old.fileHashes(), old.sourceFingerprint(),
                    old.createdAt(), BuildVersion.State.ARCHIVED);
            versions.put(old.versionId(), archived);
            writeVersion(archived);
            // GC products no longer referenced by any retained version
            for (String hash : old.manifest().values()) {
                if (!retainedHashes.contains(hash)) {
                    products.remove(hash);
                    try {
                        Files.deleteIfExists(productsDir.resolve(hash + ".json"));
                    } catch (IOException e) {
                        log.warn("Failed to delete product file {}", hash, e);
                    }
                }
            }
            log.info("Archived version {}", old.versionId());
        }
    }

    private void writeProduct(ContentProduct product) {
        try {
            Path f = productsDir.resolve(product.contentHash() + ".json");
            if (Files.exists(f)) return;
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), product);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write product " + product.docId(), e);
        }
    }

    private void writeVersion(BuildVersion version) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(versionsDir.resolve(version.versionId() + ".json").toFile(), version);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write version " + version.versionId(), e);
        }
    }

    private void writeCurrent(long versionId) {
        try {
            Files.writeString(currentFile, Long.toString(versionId));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write current pointer", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        // versions/products are written incrementally; nothing to flush
    }
}
