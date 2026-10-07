package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interleaves incremental rebuilds (with link-existence changes), concurrent
 * reads and concurrent rollbacks. At any moment, reads must observe one
 * complete committed version - never a mix of old and new products - and
 * rebuilds must not block readers.
 */
class ConcurrentRebuildReadRollbackTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAlwaysSeeACompleteCommittedVersion() throws Exception {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.properties.setVersionRetention(100_000); // keep every version readable
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        for (int i = 0; i < 10; i++) {
            fx.write("doc" + i + ".md", "# Doc " + i + "\nSee [home](index.md).\n");
        }
        assertThat(fx.engine.fullBuild().success()).isTrue();

        int writerOps = 40;
        int readerThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistencies = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();

        // Writer: toggle the linked document and rebuild incrementally.
        var writer = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < writerOps; i++) {
                    if (i % 2 == 0) {
                        fx.delete("guide.md");
                    } else {
                        fx.write("guide.md", "# Guide\n");
                    }
                    if (!fx.engine.incrementalBuild().success()) {
                        errors.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        // Rollback thread: roll back to random historical versions.
        var rollbacker = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < writerOps; i++) {
                    List<BuildVersion> history = fx.versionStore.history();
                    BuildVersion target = history.get(i % history.size());
                    if (target.state() == BuildVersion.State.ACTIVE) {
                        fx.versionStore.rollbackTo(target.versionId());
                    }
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });

        // Readers: every observed version must be internally complete.
        for (int t = 0; t < readerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                        BuildVersion v = fx.versionStore.current();
                        for (Map.Entry<DocId, String> e : v.manifest().entrySet()) {
                            ContentProduct p = fx.versionStore.productByHash(e.getValue()).orElse(null);
                            if (p == null || !p.docId().equals(e.getKey())
                                    || !p.contentHash().equals(e.getValue())) {
                                inconsistencies.incrementAndGet();
                            }
                        }
                        reads.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        start.countDown();
        // Writers finish on their own; readers loop until interrupted.
        writer.get(60, TimeUnit.SECONDS);
        rollbacker.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        assertThat(inconsistencies.get()).isZero();
        assertThat(reads.get()).isGreaterThan(50);

        // The store is still coherent: the current version is committed and
        // every active version's products are readable.
        BuildVersion current = fx.versionStore.current();
        assertThat(fx.versionStore.version(current.versionId())).isPresent();
        for (BuildVersion v : fx.versionStore.history()) {
            if (v.state() == BuildVersion.State.ACTIVE) {
                for (String hash : v.manifest().values()) {
                    assertThat(fx.versionStore.productByHash(hash)).isPresent();
                }
            }
        }
    }
}
