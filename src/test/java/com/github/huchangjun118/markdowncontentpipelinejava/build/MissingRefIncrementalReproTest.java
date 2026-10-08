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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the stale-reference hazard under missing-ref-policy=WARN:
 * deleting/renaming/readding a referenced document must change the
 * referencing product's id, incremental must match full, rollback must be
 * stable.
 */
class MissingRefIncrementalReproTest {

    @TempDir
    Path tempDir;

    @Test
    void deletingReferencedDocChangesReferrerProductAndIncrementalMatchesFull() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("index.md", "# Home\nSee [the guide](guide.md) please.\n");
        fx.write("guide.md", "# Guide\nDetails.\n");
        fx.engine.fullBuild();

        DocId index = DocId.fromPath("index.md");
        String v1Hash = fx.versionStore.current().manifest().get(index);
        ContentProduct v1Product = fx.versionStore.product(index).orElseThrow();
        assertThat(v1Product.html()).contains("href=\"content/guide\"");

        // Delete the referenced doc and incrementally rebuild.
        fx.delete("guide.md");
        BuildReport inc = fx.engine.incrementalBuild();
        assertThat(inc.success()).isTrue();
        String incHash = fx.versionStore.current().manifest().get(index);
        ContentProduct incProduct = fx.versionStore.product(index).orElseThrow();

        // (1) content changed -> identifier must change
        assertThat(incHash).as("product id must change when rendered content changes").isNotEqualTo(v1Hash);
        // link must now render as external (missing reference under WARN)
        assertThat(incProduct.html()).contains("href=\"guide.md\"");
        assertThat(incProduct.html()).doesNotContain("content/guide");

        // Snapshot the incremental state for comparison, then do a full build
        // from the same source tree.
        long incVersionId = fx.versionStore.current().versionId();
        BuildReport full = fx.engine.fullBuild();
        String fullHash = fx.versionStore.current().manifest().get(index);
        assertThat(fullHash).isEqualTo(incHash);
        ContentProduct fullProduct = fx.versionStore.product(index).orElseThrow();
        assertThat(fullProduct.html()).isEqualTo(incProduct.html());
    }

    @Test
    void rollbackReadsHistoricalContentEvenAfterReferenceDisappeared() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("index.md", "# Home\nSee [the guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n");
        BuildReport r1 = fx.engine.fullBuild();
        BuildVersion v1 = fx.versionStore.version(r1.versionId()).orElseThrow();

        DocId index = DocId.fromPath("index.md");
        ContentProduct v1Product = fx.versionStore.product(index).orElseThrow();

        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        ContentProduct v2Product = fx.versionStore.product(index).orElseThrow();
        assertThat(v2Product.html()).isNotEqualTo(v1Product.html());

        // Roll back to v1: must read exactly the v1 content.
        fx.versionStore.rollbackTo(v1.versionId());
        ContentProduct rolled = fx.versionStore.product(index).orElseThrow();
        assertThat(rolled.html()).isEqualTo(v1Product.html());
        assertThat(rolled.contentHash()).isEqualTo(v1Product.contentHash());

        // Rebuilds after rollback must not mutate historical content.
        fx.engine.incrementalBuild();
        fx.versionStore.rollbackTo(v1.versionId());
        ContentProduct rolledAgain = fx.versionStore.product(index).orElseThrow();
        assertThat(rolledAgain.html()).isEqualTo(v1Product.html());
    }
}
