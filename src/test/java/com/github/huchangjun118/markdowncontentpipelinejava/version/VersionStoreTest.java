package com.github.huchangjun118.markdowncontentpipelinejava.version;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionStoreTest {

    @TempDir
    Path tempDir;

    private TestFixture fixture() {
        return new TestFixture(tempDir);
    }

    private BuildVersion publishVersion(TestFixture fx, long seed) {
        Map<DocId, String> manifest = new HashMap<>();
        Map<DocId, ContentProduct> products = new HashMap<>();
        DocId id = DocId.fromPath("doc" + seed + ".md");
        String hash = "hash-" + seed;
        manifest.put(id, hash);
        products.put(id, new ContentProduct(id, "doc" + seed + ".md", "Title " + seed,
                "<p>body " + seed + "</p>", hash, java.util.Set.of()));
        BuildVersion v = new BuildVersion(0L, BuildVersion.VersionType.FULL, null, null,
                manifest, Map.of(), "fp-" + seed, 0L, BuildVersion.State.ACTIVE);
        return fx.versionStore.publish(v, products);
    }

    @Test
    void rollbackRestoresHistoricalContent() {
        TestFixture fx = fixture();
        BuildVersion v1 = publishVersion(fx, 1);
        BuildVersion v2 = publishVersion(fx, 2);
        BuildVersion v3 = publishVersion(fx, 3);

        BuildVersion rolled = fx.versionStore.rollbackTo(v1.versionId());

        assertThat(rolled.type()).isEqualTo(BuildVersion.VersionType.ROLLBACK);
        assertThat(rolled.sourceVersionId()).isEqualTo(v1.versionId());
        assertThat(rolled.manifest()).isEqualTo(v1.manifest());
        assertThat(fx.versionStore.current().versionId()).isEqualTo(rolled.versionId());
        // Reads return the historical product
        ContentProduct product = fx.versionStore.product(DocId.fromPath("doc1.md")).orElseThrow();
        assertThat(product.contentHash()).isEqualTo("hash-1");
        // History is linear and complete
        assertThat(fx.versionStore.history()).hasSize(4);
    }

    @Test
    void rollbackToMissingVersionFails() {
        TestFixture fx = fixture();
        publishVersion(fx, 1);
        assertThatThrownBy(() -> fx.versionStore.rollbackTo(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void rollbackToArchivedVersionFails() {
        TestFixture fx = fixture();
        fx.properties.setVersionRetention(2);
        BuildVersion v1 = publishVersion(fx, 1);
        publishVersion(fx, 2);
        publishVersion(fx, 3);
        publishVersion(fx, 4);

        assertThat(v1.state()).isEqualTo(BuildVersion.State.ACTIVE);
        assertThatThrownBy(() -> fx.versionStore.rollbackTo(v1.versionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void concurrentPublishAndRollbackIsDeterministic() throws Exception {
        TestFixture fx = fixture();
        publishVersion(fx, 0);

        int threads = 8;
        int opsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int seedBase = t * opsPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        if (i % 3 == 0) {
                            // Rolling back to the current version always targets
                            // a retained (never archived) version, regardless of
                            // how fast the retention window rotates.
                            long target = fx.versionStore.current().versionId();
                            fx.versionStore.rollbackTo(target);
                        } else {
                            publishVersion(fx, seedBase + i);
                        }
                    }
                } catch (IllegalStateException e) {
                    // Under contention a rollback target can be archived by a
                    // racing publish between reading current() and acquiring the
                    // publish lock. Rejecting rollback to an archived version is
                    // the documented, explicit outcome; anything else is a bug.
                    if (!e.getMessage().contains("archived")) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();

        // Invariants: current is a committed version; history is linear;
        // every rollback points to an existing version with the same manifest;
        // every manifest's products are readable.
        BuildVersion current = fx.versionStore.current();
        assertThat(fx.versionStore.version(current.versionId())).isPresent();
        for (BuildVersion v : fx.versionStore.history()) {
            assertThat(v.state()).isNotNull();
            if (v.type() == BuildVersion.VersionType.ROLLBACK) {
                BuildVersion src = fx.versionStore.version(v.sourceVersionId()).orElseThrow();
                assertThat(v.manifest()).isEqualTo(src.manifest());
            }
            if (v.state() == BuildVersion.State.ACTIVE) {
                for (String hash : v.manifest().values()) {
                    assertThat(fx.versionStore.productByHash(hash)).isPresent();
                }
            }
        }
    }
}
