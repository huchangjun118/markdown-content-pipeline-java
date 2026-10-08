package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void readsDuringRebuildSeeOnlyCommittedVersionsAndDoNotBlockBuild() throws Exception {
        TestFixture fx = new TestFixture(tempDir);
        int docCount = 40;
        for (int i = 0; i < docCount; i++) {
            fx.write("doc" + i + ".md", "# Doc " + i + "\nBody of doc " + i + ".\n");
        }

        // Slow builder: 2ms per document so a full build takes ~80ms,
        // creating a real window for concurrent reads.
        ContentBuilder slowBuilder = new ContentBuilder() {
            @Override
            public ContentProduct build(ParsedDocument doc, Map<DocId, ContentProduct> directIncludes,
                                        java.util.function.Function<String, DocId> includeResolver,
                                        java.util.function.Function<String, DocId> linkResolver,
                                        String contentHash, Set<DocId> dependencies) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.build(doc, directIncludes, includeResolver, linkResolver, contentHash, dependencies);
            }
        };
        BuildEngine slowEngine = new BuildEngine(fx.scanner, fx.parser, fx.graphBuilder,
                fx.hasher, slowBuilder, fx.cache, fx.versionStore);

        // Initial published version for readers to observe.
        fx.engine.fullBuild();
        assertThat(fx.versionStore.current().manifest()).hasSize(docCount);

        int readerThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger inconsistencies = new AtomicInteger();

        for (int t = 0; t < readerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        BuildVersion v = fx.versionStore.current();
                        // Read every product of the observed version.
                        for (Map.Entry<DocId, String> e : v.manifest().entrySet()) {
                            ContentProduct p = fx.versionStore.productByHash(e.getValue()).orElse(null);
                            if (p == null || !p.docId().equals(e.getKey())) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                        reads.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        // Rebuild must complete despite concurrent readers (not blocked).
        Future<?> buildFuture = pool.submit(slowEngine::fullBuild);
        buildFuture.get(30, TimeUnit.SECONDS);

        // Let readers run a bit longer after publish to observe the new version.
        Thread.sleep(100);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(reads.get()).as("readers should have performed many reads").isGreaterThan(50);
        assertThat(inconsistencies.get()).as("no mixed-version reads").isZero();
        assertThat(fx.versionStore.current().versionId()).isEqualTo(2L);
        assertThat(fx.versionStore.current().manifest()).hasSize(docCount);
    }

    /**
     * Reference-existence churn interleaved with concurrent readers, publishes
     * and rollbacks: every read must observe one fully committed version
     * (every manifest hash resolves to a product whose html is internally
     * consistent), reads must never block builds, and the final state must
     * equal a full rebuild.
     */
    @Test
    void referenceChurnWithConcurrentReadsPublishesAndRollbacksStaysConsistent() throws Exception {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("index.md", "# Home\nSee [g](guide.md).\n");
        fx.write("guide.md", "# Guide v1\n");
        fx.engine.fullBuild();
        long firstVersion = fx.versionStore.current().versionId();

        // Slow builder so each build spans multiple concurrent reads.
        ContentBuilder slowBuilder = new ContentBuilder() {
            @Override
            public ContentProduct build(ParsedDocument doc, Map<DocId, ContentProduct> directIncludes,
                                        java.util.function.Function<String, DocId> includeResolver,
                                        java.util.function.Function<String, DocId> linkResolver,
                                        String contentHash, Set<DocId> dependencies) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.build(doc, directIncludes, includeResolver, linkResolver, contentHash, dependencies);
            }
        };
        BuildEngine slowEngine = new BuildEngine(fx.scanner, fx.parser, fx.graphBuilder,
                fx.hasher, slowBuilder, fx.cache, fx.versionStore);

        int readerThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 2);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger inconsistencies = new AtomicInteger();
        CountDownLatch readersStarted = new CountDownLatch(readerThreads);

        for (int t = 0; t < readerThreads; t++) {
            pool.submit(() -> {
                readersStarted.countDown();
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    BuildVersion v = fx.versionStore.current();
                    for (Map.Entry<DocId, String> e : v.manifest().entrySet()) {
                        ContentProduct p = fx.versionStore.productByHash(e.getValue()).orElse(null);
                        // Every manifest entry must resolve, match its doc id and
                        // carry the same identifier the manifest names.
                        if (p == null || !p.docId().equals(e.getKey())
                                || !p.contentHash().equals(e.getValue())) {
                            inconsistencies.incrementAndGet();
                        }
                    }
                    reads.incrementAndGet();
                }
            });
        }
        readersStarted.await();

        // One mutator thread churns the referenced document through builds.
        Future<?> mutator = pool.submit(() -> {
            for (int i = 0; i < 10; i++) {
                if (i % 2 == 0) {
                    fx.delete("guide.md");
                } else {
                    fx.write("guide.md", "# Guide v" + i + "\n");
                }
                slowEngine.incrementalBuild();
                // Occasional rollback interleaved with churn and readers.
                fx.versionStore.rollbackTo(firstVersion);
            }
        });
        mutator.get(30, TimeUnit.SECONDS);
        stop.set(true);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(inconsistencies.get()).as("no mixed or unreadable versions at any instant").isZero();
        assertThat(reads.get()).isGreaterThan(50);

        // Final convergence: restore the guide and confirm incremental == full.
        fx.write("guide.md", "# Guide final\n");
        slowEngine.incrementalBuild();
        Map<DocId, String> incremental = fx.versionStore.current().manifest();
        slowEngine.fullBuild();
        assertThat(fx.versionStore.current().manifest()).isEqualTo(incremental);
    }
}
