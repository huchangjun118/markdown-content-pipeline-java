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
 * Reproduces the stale-product-identifier bug: under
 * {@code pipeline.missing-ref-policy=WARN}, deleting / renaming / re-adding a
 * linked document changes the rendered output of its referrers, so their
 * product identifiers must change too, incremental must equal full, and
 * rollback must keep serving the historical content.
 */
class LinkExistenceRebuildTest {

    @TempDir
    Path tempDir;

    private TestFixture fixture() {
        return new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
    }

    @Test
    void deletingLinkedDocChangesReferrerProductIdentifier() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        BuildReport v1 = fx.engine.fullBuild();
        assertThat(v1.success()).isTrue();

        DocId index = DocId.fromPath("index.md");
        String hashV1 = fx.versionStore.current().manifest().get(index);
        ContentProduct productV1 = fx.versionStore.product(index).orElseThrow();
        assertThat(productV1.html()).contains("href=\"content/guide\"");

        fx.delete("guide.md");
        BuildReport v2 = fx.engine.incrementalBuild();
        assertThat(v2.success()).isTrue();

        String hashV2 = fx.versionStore.current().manifest().get(index);
        ContentProduct productV2 = fx.versionStore.product(index).orElseThrow();
        // Content changed: the link now renders as an external/raw link.
        assertThat(productV2.html()).contains("href=\"guide.md\"");
        // The identifier must change with the content.
        assertThat(hashV2).isNotEqualTo(hashV1);
        assertThat(productV2.contentHash()).isEqualTo(hashV2);
    }

    @Test
    void incrementalAfterDeleteEqualsFullBuild() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.write("other.md", "# Other\n");
        fx.engine.fullBuild();

        fx.delete("guide.md");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        Map<DocId, String> incrementalManifest = fx.versionStore.current().manifest();
        Map<DocId, String> incrementalHtml = readAllHtml(fx);

        BuildReport full = fx.engine.fullBuild();
        assertThat(full.success()).isTrue();
        assertThat(fx.versionStore.current().manifest()).isEqualTo(incrementalManifest);
        assertThat(readAllHtml(fx)).isEqualTo(incrementalHtml);
    }

    @Test
    void rollbackAfterDeleteReadsHistoricalContent() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.engine.fullBuild();
        DocId index = DocId.fromPath("index.md");
        long versionWithGuide = fx.versionStore.current().versionId();
        String htmlWithGuide = fx.versionStore.product(index).orElseThrow().html();

        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        assertThat(fx.versionStore.product(index).orElseThrow().html())
                .isNotEqualTo(htmlWithGuide);

        fx.versionStore.rollbackTo(versionWithGuide);
        // The historical version must read exactly what it published.
        assertThat(fx.versionStore.product(index).orElseThrow().html())
                .isEqualTo(htmlWithGuide);
        assertThat(fx.versionStore.product(DocId.fromPath("guide.md"))).isPresent();
    }

    @Test
    void renamingLinkedDocMatchesFreshFullBuild() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.write("unrelated.md", "# Unrelated\n");
        fx.engine.fullBuild();
        DocId index = DocId.fromPath("index.md");
        DocId unrelated = DocId.fromPath("unrelated.md");
        String unrelatedHashBefore = fx.versionStore.current().manifest().get(unrelated);

        // Rename guide.md -> handbook.md (delete + add).
        fx.delete("guide.md");
        fx.write("handbook.md", "# Guide\n");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();

        // The referrer now links to nothing (its old target is gone) ...
        ContentProduct indexProduct = fx.versionStore.product(index).orElseThrow();
        assertThat(indexProduct.html()).contains("href=\"guide.md\"");
        // ... and the unrelated document kept its identifier.
        assertThat(fx.versionStore.current().manifest().get(unrelated))
                .isEqualTo(unrelatedHashBefore);

        assertIncrementalEqualsFreshFullBuild(fx);
    }

    @Test
    void addingPreviouslyMissingLinkTargetMatchesFreshFullBuild() {
        TestFixture fx = fixture();
        // index links to a not-yet-existing document (warning only).
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("unrelated.md", "# Unrelated\n");
        BuildReport v1 = fx.engine.fullBuild();
        assertThat(v1.success()).isTrue();
        assertThat(v1.warnings()).anyMatch(w -> w.type().name().equals("MISSING_REFERENCE"));
        DocId index = DocId.fromPath("index.md");
        assertThat(fx.versionStore.product(index).orElseThrow().html())
                .contains("href=\"guide.md\"");
        String hashBefore = fx.versionStore.current().manifest().get(index);

        // The link target appears: the referrer's output and identifier change.
        fx.write("guide.md", "# Guide\n");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        assertThat(fx.versionStore.product(index).orElseThrow().html())
                .contains("href=\"content/guide\"");
        assertThat(fx.versionStore.current().manifest().get(index)).isNotEqualTo(hashBefore);
        assertThat(inc.affected()).contains(index);

        assertIncrementalEqualsFreshFullBuild(fx);
    }

    @Test
    void deleteRollbackReAddSequenceStaysConsistentAndConverges() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.write("unrelated.md", "# Unrelated\n");
        fx.engine.fullBuild();
        DocId index = DocId.fromPath("index.md");
        DocId guide = DocId.fromPath("guide.md");
        DocId unrelated = DocId.fromPath("unrelated.md");
        long v1 = fx.versionStore.current().versionId();
        Map<DocId, String> manifestV1 = fx.versionStore.current().manifest();
        String indexHtmlV1 = fx.versionStore.product(index).orElseThrow().html();
        String unrelatedHash = manifestV1.get(unrelated);

        // Step 1: delete the linked document, rebuild incrementally.
        fx.delete("guide.md");
        BuildReport inc1 = fx.engine.incrementalBuild();
        assertThat(inc1.success()).isTrue();
        String indexHtmlAfterDelete = fx.versionStore.product(index).orElseThrow().html();
        assertThat(indexHtmlAfterDelete).contains("href=\"guide.md\"");
        assertThat(fx.versionStore.product(guide)).isEmpty();
        assertThat(fx.versionStore.current().manifest().get(unrelated)).isEqualTo(unrelatedHash);

        // Step 2: roll back to the version where the link target existed.
        fx.versionStore.rollbackTo(v1);
        assertThat(fx.versionStore.product(index).orElseThrow().html()).isEqualTo(indexHtmlV1);
        assertThat(fx.versionStore.product(guide)).isPresent();

        // Step 3: more rebuilds must not rewrite history: rolling back again
        // (a no-op source-wise) still serves the same historical content.
        fx.write("guide.md", "# Guide\n");
        BuildReport inc2 = fx.engine.incrementalBuild();
        assertThat(inc2.success()).isTrue();
        // Current state equals the original version's content ...
        assertThat(fx.versionStore.current().manifest()).isEqualTo(manifestV1);
        assertThat(fx.versionStore.product(index).orElseThrow().html()).isEqualTo(indexHtmlV1);
        // ... and the historical version still reads exactly as published.
        fx.versionStore.rollbackTo(v1);
        assertThat(fx.versionStore.product(index).orElseThrow().html()).isEqualTo(indexHtmlV1);

        // Step 4: delete and re-add once more; every step must converge to the
        // same state a fresh full build of the same sources would produce.
        fx.delete("guide.md");
        assertThat(fx.engine.incrementalBuild().success()).isTrue();
        assertIncrementalEqualsFreshFullBuild(fx);
        fx.write("guide.md", "# Guide\n");
        assertThat(fx.engine.incrementalBuild().success()).isTrue();
        assertThat(fx.versionStore.current().manifest()).isEqualTo(manifestV1);
        assertIncrementalEqualsFreshFullBuild(fx);
    }

    @Test
    void nestedIncludeAndLinkGraphHasPreciseAffectedRange() {
        TestFixture fx = fixture();
        // a includes b; b links c; d links b; e is unrelated.
        fx.write("a.md", "# A\n@include b.md\n");
        fx.write("b.md", "# B\nSee [c](c.md).\n");
        fx.write("c.md", "# C\n");
        fx.write("d.md", "# D\nSee [b](b.md).\n");
        fx.write("e.md", "# E\n");
        fx.engine.fullBuild();
        DocId a = DocId.fromPath("a.md");
        DocId b = DocId.fromPath("b.md");
        DocId c = DocId.fromPath("c.md");
        DocId d = DocId.fromPath("d.md");
        DocId e = DocId.fromPath("e.md");
        Map<DocId, String> m1 = fx.versionStore.current().manifest();

        // Delete c: b's link breaks (b affected); a inlines b (affected
        // transitively through the include); d only links b whose existence is
        // unchanged, so d and e must keep their identifiers and not be rebuilt.
        fx.delete("c.md");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        assertThat(inc.affected()).containsExactlyInAnyOrder(a, b);
        assertThat(inc.built()).containsExactlyInAnyOrder(a, b);
        Map<DocId, String> m2 = fx.versionStore.current().manifest();
        assertThat(m2.get(a)).isNotEqualTo(m1.get(a));
        assertThat(m2.get(b)).isNotEqualTo(m1.get(b));
        assertThat(m2.get(d)).isEqualTo(m1.get(d));
        assertThat(m2.get(e)).isEqualTo(m1.get(e));
        assertThat(fx.versionStore.product(a).orElseThrow().html())
                .contains("href=\"c.md\"");
        assertIncrementalEqualsFreshFullBuild(fx);

        // Modify d's link target b (content change only): link existence is
        // unaffected, so d must not be rebuilt either.
        fx.write("b.md", "# B changed\nSee [c](c.md).\n");
        BuildReport inc2 = fx.engine.incrementalBuild();
        assertThat(inc2.success()).isTrue();
        assertThat(inc2.affected()).containsExactlyInAnyOrder(a, b);
        Map<DocId, String> m3 = fx.versionStore.current().manifest();
        assertThat(m3.get(d)).isEqualTo(m1.get(d));
        assertThat(m3.get(e)).isEqualTo(m1.get(e));
        assertIncrementalEqualsFreshFullBuild(fx);
    }

    @Test
    void identicalSourcesAtDifferentPathsGetDistinctIdentifiers() {
        TestFixture fx = fixture();
        fx.write("one/copy.md", "# Same\nBody.\n");
        fx.write("two/copy.md", "# Same\nBody.\n");
        BuildReport report = fx.engine.fullBuild();
        assertThat(report.success()).isTrue();

        DocId one = DocId.fromPath("one/copy.md");
        DocId two = DocId.fromPath("two/copy.md");
        Map<DocId, String> manifest = fx.versionStore.current().manifest();
        // Same bytes, but the products are bound to their document identity.
        assertThat(manifest.get(one)).isNotEqualTo(manifest.get(two));
        ContentProduct p1 = fx.versionStore.product(one).orElseThrow();
        ContentProduct p2 = fx.versionStore.product(two).orElseThrow();
        assertThat(p1.docId()).isEqualTo(one);
        assertThat(p2.docId()).isEqualTo(two);
        assertThat(p1.sourcePath()).isEqualTo("one/copy.md");
        assertThat(p2.sourcePath()).isEqualTo("two/copy.md");
    }

    @Test
    void deletePropagationWorksAfterRestart() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        fx.engine.fullBuild();
        DocId index = DocId.fromPath("index.md");
        String hashBefore = fx.versionStore.current().manifest().get(index);

        // Simulate a restart: fresh component instances over the same
        // source/output directories (no in-memory graph from the last build).
        TestFixture restarted = new TestFixture(fx.sourceDir, fx.outputDir,
                PipelineProperties.MissingRefPolicy.WARN);
        assertThat(restarted.versionStore.current().manifest().get(index))
                .isEqualTo(hashBefore);

        restarted.delete("guide.md");
        BuildReport inc = restarted.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        // The referrer is detected as affected and rebuilt with a new
        // identifier even without any carried-over in-memory state.
        assertThat(inc.affected()).contains(index);
        assertThat(restarted.versionStore.current().manifest().get(index))
                .isNotEqualTo(hashBefore);
        assertThat(restarted.versionStore.product(index).orElseThrow().html())
                .contains("href=\"guide.md\"");

        TestFixture fresh = new TestFixture(fx.sourceDir,
                tempDir.resolve("fresh-output-" + System.nanoTime()),
                PipelineProperties.MissingRefPolicy.WARN);
        assertThat(fresh.engine.fullBuild().success()).isTrue();
        assertThat(restarted.versionStore.current().manifest())
                .isEqualTo(fresh.versionStore.current().manifest());
    }

    /** Compares the current version against a from-scratch full build of the
     * same source directory in a fresh output directory (no shared cache). */
    private void assertIncrementalEqualsFreshFullBuild(TestFixture fx) {
        TestFixture fresh = new TestFixture(fx.sourceDir,
                tempDir.resolve("fresh-output-" + System.nanoTime()),
                PipelineProperties.MissingRefPolicy.WARN);
        BuildReport full = fresh.engine.fullBuild();
        assertThat(full.success()).isTrue();
        // Identifiers must match document by document ...
        assertThat(fx.versionStore.current().manifest())
                .isEqualTo(fresh.versionStore.current().manifest());
        // ... and so must the rendered content of every product.
        for (Map.Entry<DocId, String> e : fresh.versionStore.current().manifest().entrySet()) {
            ContentProduct expected = fresh.versionStore.productByHash(e.getValue()).orElseThrow();
            ContentProduct actual = fx.versionStore.product(e.getKey()).orElseThrow();
            assertThat(actual).isEqualTo(expected);
        }
    }

    private Map<DocId, String> readAllHtml(TestFixture fx) {
        BuildVersion current = fx.versionStore.current();
        Map<DocId, String> html = new java.util.TreeMap<>();
        for (DocId id : current.manifest().keySet()) {
            html.put(id, fx.versionStore.product(id).orElseThrow().html());
        }
        return html;
    }
}
