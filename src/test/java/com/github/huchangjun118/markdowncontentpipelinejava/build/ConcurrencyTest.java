package com.github.huchangjun118.markdowncontentpipelinejava.build;

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
}
