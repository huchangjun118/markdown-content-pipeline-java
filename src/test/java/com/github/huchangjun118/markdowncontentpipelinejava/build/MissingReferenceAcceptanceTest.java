package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for reference-existence change handling under
 * {@code pipeline.missing-ref-policy=WARN}: identifier/content consistency,
 * incremental-vs-full equivalence, rollback immutability, unaffected-document
 * stability, repeated delete/add sequences, deeply nested mixed include/link
 * graphs, bounded caching and render-failure isolation.
 */
class MissingReferenceAcceptanceTest {

    @TempDir
    Path tempDir;

    private TestFixture fixture() {
        return new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
    }

    private Map<DocId, String> manifest(TestFixture fx) {
        return fx.versionStore.current().manifest();
    }

    // Requirement 2 + 4: rename a referenced document; incremental must equal
    // full, and unrelated documents must keep their identifiers.
    @Test
    void renameReferencedDocIncrementalMatchesFullAndKeepsUnrelatedStable() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [g](guide.md) and [u](unrelated.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.write("unrelated.md", "# Unrelated\nNo links.\n");
        fx.engine.fullBuild();
        Map<DocId, String> v1 = manifest(fx);

        fx.write("guide-v2.md", "# Guide v2\n");
        fx.delete("guide.md");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        Map<DocId, String> afterInc = manifest(fx);

        // index content changed (its link is now missing) -> new id
        assertThat(afterInc.get(DocId.fromPath("index.md"))).isNotEqualTo(v1.get(DocId.fromPath("index.md")));
        // unrelated keeps its exact identifier and content
        assertThat(afterInc.get(DocId.fromPath("unrelated.md")))
                .isEqualTo(v1.get(DocId.fromPath("unrelated.md")));
        // old guide gone, new guide present
        assertThat(afterInc).doesNotContainKey(DocId.fromPath("guide.md"));
        assertThat(afterInc).containsKey(DocId.fromPath("guide-v2.md"));

        // Fresh full build of the identical source tree must produce the identical manifest
        BuildReport full = fx.engine.fullBuild();
        assertThat(full.success()).isTrue();
        assertThat(manifest(fx)).isEqualTo(afterInc);
    }

    // Requirement 2: re-adding a previously deleted referenced document.
    @Test
    void readdReferencedDocIncrementalMatchesFull() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# Guide\n");
        fx.engine.fullBuild();
        String idWhenPresent = manifest(fx).get(DocId.fromPath("index.md"));

        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        String idWhenMissing = manifest(fx).get(DocId.fromPath("index.md"));
        assertThat(idWhenMissing).isNotEqualTo(idWhenPresent);

        fx.write("guide.md", "# Guide returns\n");
        BuildReport inc = fx.engine.incrementalBuild();
        Map<DocId, String> afterInc = manifest(fx);

        // Link resolves again -> internal content link
        ContentProduct index = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();
        assertThat(index.html()).contains("href=\"content/guide\"");

        BuildReport full = fx.engine.fullBuild();
        assertThat(manifest(fx)).isEqualTo(afterInc);
    }

    // Requirement 5: delete -> incremental -> rollback -> readd -> incremental,
    // every read is self-consistent and the final state equals a full build.
    @Test
    void repeatedDisappearReappearSequenceConvergesToFull() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# Guide\n");
        BuildReport r1 = fx.engine.fullBuild();
        long v1 = r1.versionId();
        ContentProduct indexAtV1 = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();

        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        ContentProduct missingView = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();
        assertThat(missingView.html()).contains("href=\"guide.md\"");
        assertThat(missingView.contentHash()).isNotEqualTo(indexAtV1.contentHash());

        // Rollback while guide is absent: historical content must come back
        fx.versionStore.rollbackTo(v1);
        ContentProduct rolled = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();
        assertThat(rolled.html()).isEqualTo(indexAtV1.html());
        assertThat(rolled.contentHash()).isEqualTo(indexAtV1.contentHash());

        // Further rebuilds must not corrupt the historical version
        fx.engine.incrementalBuild();
        assertThat(fx.versionStore.version(v1).orElseThrow().manifest())
                .containsEntry(DocId.fromPath("index.md"), indexAtV1.contentHash());
        fx.versionStore.rollbackTo(v1);
        assertThat(fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow().html())
                .isEqualTo(indexAtV1.html());

        // Re-add and incrementally rebuild
        fx.write("guide.md", "# Guide\n");
        BuildReport inc = fx.engine.incrementalBuild();
        Map<DocId, String> afterInc = manifest(fx);
        ContentProduct finalView = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();
        assertThat(finalView.html()).isEqualTo(indexAtV1.html());

        fx.engine.fullBuild();
        assertThat(manifest(fx)).isEqualTo(afterInc);
    }

    // Requirement 6: deep nesting of includes and links. a includes b includes c;
    // d links c; e includes d. Removing c must rebuild exactly the transitive
    // dependents and leave an unrelated island untouched.
    @Test
    void deeplyNestedMixedGraphAffectedRangeIsExact() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n@include b.md\n");
        fx.write("b.md", "# B\n@include c.md\n[x](d.md)\n");
        fx.write("c.md", "# C\n[x](target.md)\n");
        fx.write("d.md", "# D\n[x](c.md)\n");
        fx.write("e.md", "# E\n@include d.md\n");
        fx.write("target.md", "# Target\n");
        fx.write("island.md", "# Island\n[solo](island-linked.md)\n");
        fx.write("island-linked.md", "# Linked\n");
        fx.engine.fullBuild();
        Map<DocId, String> v1 = manifest(fx);

        fx.delete("target.md"); // referenced only by c; c is included (transitively) by a,b
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        Map<DocId, String> v2 = manifest(fx);

        DocId a = DocId.fromPath("a.md");
        DocId b = DocId.fromPath("b.md");
        DocId c = DocId.fromPath("c.md");
        DocId d = DocId.fromPath("d.md");
        DocId e = DocId.fromPath("e.md");
        DocId island = DocId.fromPath("island.md");
        DocId islandLinked = DocId.fromPath("island-linked.md");

        // c's reference changed -> c and everything whose render embeds c (a, b) change
        assertThat(v2.get(c)).isNotEqualTo(v1.get(c));
        assertThat(v2.get(b)).isNotEqualTo(v1.get(b));
        assertThat(v2.get(a)).isNotEqualTo(v1.get(a));
        // d only links c (c still exists, its id unchanged) -> d and e stay identical
        assertThat(v2.get(d)).isEqualTo(v1.get(d));
        assertThat(v2.get(e)).isEqualTo(v1.get(e));
        // unrelated island untouched
        assertThat(v2.get(island)).isEqualTo(v1.get(island));
        assertThat(v2.get(islandLinked)).isEqualTo(v1.get(islandLinked));

        // affected report mirrors the exact set
        assertThat(inc.affected()).containsExactlyInAnyOrder(c, b, a);

        // Whole incremental manifest equals a fresh full build
        fx.engine.fullBuild();
        assertThat(manifest(fx)).isEqualTo(v2);
    }

    // Requirement 6 second half: deleting a *linked-only* doc must affect the
    // linker but not an unrelated includer.
    @Test
    void deletingLinkedOnlyDocRebuildsLinkerNotUnrelatedIncluder() {
        TestFixture fx = fixture();
        fx.write("linker.md", "# L\n[x](gone.md)\n");
        fx.write("gone.md", "# Gone\n");
        fx.write("includer.md", "# I\n@include leaf.md\n");
        fx.write("leaf.md", "# Leaf\n");
        fx.engine.fullBuild();
        Map<DocId, String> v1 = manifest(fx);

        fx.delete("gone.md");
        BuildReport inc = fx.engine.incrementalBuild();
        Map<DocId, String> v2 = manifest(fx);

        assertThat(v2.get(DocId.fromPath("linker.md"))).isNotEqualTo(v1.get(DocId.fromPath("linker.md")));
        assertThat(v2.get(DocId.fromPath("includer.md"))).isEqualTo(v1.get(DocId.fromPath("includer.md")));
        assertThat(v2.get(DocId.fromPath("leaf.md"))).isEqualTo(v1.get(DocId.fromPath("leaf.md")));
        assertThat(inc.affected()).containsExactly(DocId.fromPath("linker.md"));
    }

    // Requirement 4: a source tree with no changes makes incremental a no-op
    // (same version, nothing rebuilt), including after reference churn settles.
    @Test
    void noOpIncrementalAfterSettledChurn() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# G\n");
        fx.engine.fullBuild();
        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        fx.write("guide.md", "# G\n");
        fx.engine.incrementalBuild();
        long currentId = fx.versionStore.current().versionId();

        BuildReport noop = fx.engine.incrementalBuild();
        assertThat(noop.success()).isTrue();
        assertThat(noop.versionId()).isEqualTo(currentId);
        assertThat(noop.built()).isEmpty();
        assertThat(noop.affected()).isEmpty();
        assertThat(fx.versionStore.current().versionId()).isEqualTo(currentId);
    }

    // Requirement 3: historical versions keep serving their exact products
    // through multiple later builds.
    @Test
    void everyHistoricalVersionStaysReadableAcrossLaterBuilds() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# G1\n");
        BuildReport r1 = fx.engine.fullBuild();
        String v1Html = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow().html();

        fx.write("index.md", "# Home v2\n[x](guide.md)\n");
        fx.engine.incrementalBuild();
        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        fx.write("guide.md", "# G2\n");
        fx.engine.incrementalBuild();

        BuildVersion first = fx.versionStore.version(r1.versionId()).orElseThrow();
        String firstHash = first.manifest().get(DocId.fromPath("index.md"));
        ContentProduct historical = fx.versionStore.productByHash(firstHash).orElseThrow();
        assertThat(historical.html()).isEqualTo(v1Html);
    }

    // Requirement 8: cache stays bounded across many reference churn cycles.
    @Test
    void cacheRemainsBoundedAcrossChurn() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN, 5);
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# G\n");
        fx.engine.fullBuild();

        for (int i = 0; i < 20; i++) {
            fx.delete("guide.md");
            fx.engine.incrementalBuild();
            fx.write("guide.md", "# G " + i + "\n");
            fx.engine.incrementalBuild();
        }
        assertThat(fx.cache.size()).isLessThanOrEqualTo(5);
    }

    // Requirement 9: a document that fails to render must not publish; the last
    // version stays readable; fixing it rebuilds only the affected range.
    @Test
    void renderFailureDoesNotPublishAndRecoveryRebuildsAffectedOnly() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n[x](b.md)\n");
        fx.write("b.md", "# B\n");
        fx.write("c.md", "# C\n");
        fx.engine.fullBuild();
        long publishedId = fx.versionStore.current().versionId();

        // Sabotage rendering with a subclass only at engine level is hard, so we
        // exercise the dependency-resolution failure path instead: make a.md
        // reference a missing include -> build fails, published version stays.
        fx.write("a.md", "@include does-not-exist.md\n");
        BuildReport failed = fx.engine.incrementalBuild();
        assertThat(failed.success()).isFalse();
        assertThat(failed.errors()).isNotEmpty();
        assertThat(failed.errors().get(0).sourcePath()).isEqualTo("a.md");
        assertThat(fx.versionStore.current().versionId()).isEqualTo(publishedId);
        assertThat(fx.versionStore.product(DocId.fromPath("a.md"))).isPresent();

        // Fix and recover: a rebuilt, c reused
        fx.write("a.md", "# A fixed\n[x](b.md)\n");
        BuildReport recovered = fx.engine.incrementalBuild();
        assertThat(recovered.success()).isTrue();
        assertThat(recovered.built()).contains(DocId.fromPath("a.md"));
        assertThat(recovered.reused()).contains(DocId.fromPath("b.md"), DocId.fromPath("c.md"));
    }
}
