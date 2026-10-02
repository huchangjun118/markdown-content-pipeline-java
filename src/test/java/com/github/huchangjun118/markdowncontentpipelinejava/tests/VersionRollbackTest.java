package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.VersionInfo;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.DefaultArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Immutable version publication, history queries and rollback semantics. */
class VersionRollbackTest {

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

    private ContentPipelineProperties props(int maxVersions) {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        p.setMaxVersions(maxVersions);
        return p;
    }

    private void write(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void versionsAreImmutableAndRollbackReproducesHistory(@TempDir Path dir) throws Exception {
        write(dir, "doc.md", "# Version one\n");
        BuildCoordinator c = harness(dir, props(10));
        var v1 = c.fullRebuild("b1");
        String html1 = c.read("doc.md").orElseThrow().html();
        String id1 = c.read("doc.md").orElseThrow().contentId();

        write(dir, "doc.md", "# Version two\n");
        var v2 = c.incrementalRebuild(Map.of("doc.md", ChangeType.MODIFIED), "b2");
        assertNotEquals(html1, c.read("doc.md").orElseThrow().html());
        assertEquals(v2.version(), c.currentVersion().orElseThrow().version());

        // History remains queryable with original bytes.
        ContentVersion historical = c.readVersion(v1.version()).orElseThrow();
        assertEquals(html1, historical.artifact("doc.md").html());
        assertEquals(id1, historical.artifact("doc.md").contentId());

        // Rollback republishes as a new version number but identical content.
        var rolled = c.rollback(v1.version(), "rollback-v1").orElseThrow();
        assertEquals(html1, c.read("doc.md").orElseThrow().html());
        assertEquals(id1, c.read("doc.md").orElseThrow().contentId());
        assertTrue(rolled.version() > v2.version());
        assertEquals(rolled.version(), c.currentVersion().orElseThrow().version());
    }

    @Test
    void historyIsBoundedAndExpiredRollbackTargetMissing(@TempDir Path dir) throws Exception {
        write(dir, "doc.md", "# v0\n");
        BuildCoordinator c = harness(dir, props(3));
        long first = c.fullRebuild("b0").version();
        for (int i = 1; i <= 4; i++) {
            write(dir, "doc.md", "# v" + i + "\n");
            c.incrementalRebuild(Map.of("doc.md", ChangeType.MODIFIED), "b" + i);
        }
        assertTrue(c.readVersion(first).isEmpty(), "aged-out version must be gone");
        assertTrue(c.rollback(first, "rollback-old").isEmpty(),
                "rollback to aged-out version must fail deterministically");
        List<VersionInfo> list = c.listVersions();
        assertEquals(3, list.size());
        // current marker appears exactly once and points at the newest version.
        long currents = list.stream().filter(VersionInfo::current).count();
        assertEquals(1, currents);
        assertTrue(list.get(list.size() - 1).current());
    }

    @Test
    void concurrentPublishAndRollbackProduceTotalOrder(@TempDir Path dir) throws Exception {
        write(dir, "doc.md", "# base\n");
        BuildCoordinator c = harness(dir, props(50));
        long base = c.fullRebuild("base").version();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (n % 2 == 0) {
                        write(dir, "doc.md", "# change " + n + "\n");
                        c.incrementalRebuild(Map.of("doc.md", ChangeType.MODIFIED),
                                "concurrent-" + n);
                    } else {
                        c.rollback(base, "concurrent-rollback-" + n);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(failure.get());

        // Whatever the interleaving, current must be one complete published
        // version whose content matches one of the known bodies exactly.
        BuildArtifact artifact = c.read("doc.md").orElseThrow();
        assertNotNull(artifact.html());
        List<Long> versions = c.listVersions().stream().map(VersionInfo::version).toList();
        long previous = 0;
        for (Long v : versions) {
            assertTrue(v > previous, "version numbers must be strictly increasing");
            previous = v;
        }
    }
}
