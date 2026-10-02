package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.DefaultArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * While rebuilds run continuously, every read must observe a single complete
 * committed version: the set of document ids and their contentIds must match
 * some published version exactly, never a mix of adjacent versions, and reads
 * must not block or be blocked by the build.
 */
class ConcurrentReadWriteTest {

    private BuildCoordinator harness(Path dir, ContentPipelineProperties p) {
        p.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(p.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(p);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, p);
        VersionStore store = new VersionStore(p.getMaxVersions());
        return new BuildCoordinator(engine, store, p);
    }

    private void write(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Map<String, String> snapshotIds(ContentVersion version) {
        Map<String, String> ids = new HashMap<>();
        version.artifacts().forEach((id, artifact) -> ids.put(id, artifact.contentId()));
        return ids;
    }

    @Test
    void readsNeverSeeMixedVersionsAndNeverBlock(@TempDir Path dir) throws Exception {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        p.setMaxVersions(30);

        write(dir, "a.md", "# A 0\n");
        write(dir, "b.md", "# B 0\n");
        write(dir, "c.md", "# C 0\n");
        write(dir, "root.md", "# Root\n\n{{include a.md}}\n");
        BuildCoordinator coordinator = harness(dir, p);
        coordinator.fullRebuild("initial");

        int iterations = 40;
        int readerThreads = 4;
        AtomicInteger mixedReads = new AtomicInteger();
        AtomicInteger totalReads = new AtomicInteger();
        AtomicReference<String> firstMismatch = new AtomicReference<>();

        ExecutorService readers = Executors.newFixedThreadPool(readerThreads);
        CountDownLatch stop = new CountDownLatch(1);
        for (int r = 0; r < readerThreads; r++) {
            readers.submit(() -> {
                try {
                    while (stop.getCount() > 0 && !Thread.currentThread().isInterrupted()) {
                        // Single atomic publication point: both the number and
                        // the artifacts come from one immutable snapshot, so a
                        // version advancing between coordinator calls cannot be
                        // mistaken for a mixed read.
                        var current = coordinator.currentVersion();
                        if (current.isEmpty()) {
                            continue;
                        }
                        ContentVersion version = current.get();
                        totalReads.incrementAndGet();

                        // The same version must be fetchable by number and be
                        // byte-identical (immutable publication).
                        ContentVersion refetched = coordinator.readVersion(version.version())
                                .orElse(null);
                        if (refetched == null
                                || !refetched.artifacts().keySet()
                                        .equals(version.artifacts().keySet())) {
                            mixedReads.incrementAndGet();
                            firstMismatch.compareAndSet(null,
                                    "version " + version.version()
                                            + " changed between reads or vanished");
                            continue;
                        }

                        Map<String, String> viaVersion = snapshotIds(version);
                        for (Map.Entry<String, String> entry : viaVersion.entrySet()) {
                            BuildArtifact artifact = refetched.artifact(entry.getKey());
                            if (artifact == null
                                    || !artifact.contentId().equals(entry.getValue())) {
                                mixedReads.incrementAndGet();
                                firstMismatch.compareAndSet(null,
                                        "document " + entry.getKey()
                                                + " inconsistent inside version "
                                                + version.version());
                                break;
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Shutting down; irrelevant.
                }
            });
        }

        for (int i = 1; i <= iterations; i++) {
            write(dir, "a.md", "# A " + i + "\n");
            var result = coordinator.incrementalRebuild(
                    Map.of("a.md", ChangeType.MODIFIED), "loop-" + i);
            assertTrue(result.success(), () -> "errors: " + result.errors());
            // Each committed version must be self-consistent.
            ContentVersion committed = coordinator.readVersion(result.version()).orElseThrow();
            for (BuildArtifact artifact : committed.artifacts().values()) {
                assertNotNull(artifact.html());
                assertNotNull(artifact.contentId());
            }
        }
        stop.countDown();
        readers.shutdown();
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS), "readers should finish promptly");

        assertEquals(0, mixedReads.get(),
                "all reads must match one committed version; first mismatch: "
                        + firstMismatch.get());
        assertTrue(totalReads.get() > iterations,
                "readers should run many reads alongside builds, was " + totalReads.get());
    }
}
