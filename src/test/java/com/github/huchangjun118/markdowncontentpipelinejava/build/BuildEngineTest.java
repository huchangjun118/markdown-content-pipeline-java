package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildEngineTest {

    @TempDir
    Path tempDir;

    private TestFixture fixture() {
        return new TestFixture(tempDir);
    }

    @Test
    void fullBuildPublishesVersionAndRendersProducts() {
        TestFixture fx = fixture();
        fx.write("index.md", "# Home\nWelcome to the [guide](guide.md).\n");
        fx.write("guide.md", "# Guide\n@include shared/notice.md\n");
        fx.write("shared/notice.md", "Notice text.\n");

        BuildReport report = fx.engine.fullBuild();

        assertThat(report.success()).isTrue();
        assertThat(report.versionId()).isEqualTo(1L);
        assertThat(fx.versionStore.hasCurrent()).isTrue();
        BuildVersion current = fx.versionStore.current();
        assertThat(current.manifest()).hasSize(3);

        var home = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow();
        assertThat(home.html()).contains("<h1 id=\"home\">Home</h1>");
        assertThat(home.html()).contains("href=\"content/guide\"");
        var guide = fx.versionStore.product(DocId.fromPath("guide.md")).orElseThrow();
        assertThat(guide.html()).contains("Notice text.");
        assertThat(guide.dependencies()).contains(DocId.fromPath("shared/notice.md"));
    }

    @Test
    void repeatedFullBuildReusesCache() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n");
        fx.write("b.md", "@include a.md\n");

        BuildReport first = fx.engine.fullBuild();
        BuildReport second = fx.engine.fullBuild();

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(second.built()).isEmpty();
        assertThat(second.reused()).hasSize(2);
        assertThat(fx.versionStore.history()).hasSize(2);
    }

    @Test
    void sameInputsProduceIdenticalVersionsAcrossStores() {
        TestFixture fx1 = fixture();
        fx1.write("a.md", "# A\n[x](b.md)\n");
        fx1.write("b.md", "# B\n");
        BuildReport r1 = fx1.engine.fullBuild();

        TestFixture fx2 = fixture();
        fx2.write("a.md", "# A\n[x](b.md)\n");
        fx2.write("b.md", "# B\n");
        BuildReport r2 = fx2.engine.fullBuild();

        assertThat(r1.success()).isTrue();
        assertThat(r2.success()).isTrue();
        assertThat(fx1.versionStore.current().manifest())
                .isEqualTo(fx2.versionStore.current().manifest());
    }

    @Test
    void incrementalRebuildOnlyAffectsDownstreamAndMatchesFullBuild() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n");
        fx.write("b.md", "@include a.md\n");
        fx.write("c.md", "# C\n");
        fx.write("d.md", "[x](c.md)\n");

        BuildReport v1 = fx.engine.fullBuild();
        Map<DocId, String> manifest1 = fx.versionStore.current().manifest();

        // Change a.md: b includes a, so b is affected; c and d are not.
        fx.write("a.md", "# A changed\n");
        BuildReport v2 = fx.engine.incrementalBuild();

        assertThat(v2.success()).isTrue();
        assertThat(v2.affected()).contains(DocId.fromPath("a.md"), DocId.fromPath("b.md"));
        assertThat(v2.affected()).doesNotContain(DocId.fromPath("c.md"), DocId.fromPath("d.md"));
        assertThat(v2.built()).contains(DocId.fromPath("a.md"), DocId.fromPath("b.md"));
        assertThat(v2.reused()).contains(DocId.fromPath("c.md"), DocId.fromPath("d.md"));

        Map<DocId, String> manifest2 = fx.versionStore.current().manifest();
        // Unaffected products keep their identifiers and hashes
        assertThat(manifest2.get(DocId.fromPath("c.md"))).isEqualTo(manifest1.get(DocId.fromPath("c.md")));
        assertThat(manifest2.get(DocId.fromPath("d.md"))).isEqualTo(manifest1.get(DocId.fromPath("d.md")));
        // Affected products changed
        assertThat(manifest2.get(DocId.fromPath("a.md"))).isNotEqualTo(manifest1.get(DocId.fromPath("a.md")));
        assertThat(manifest2.get(DocId.fromPath("b.md"))).isNotEqualTo(manifest1.get(DocId.fromPath("b.md")));

        // Incremental result must equal a fresh full build
        BuildReport v3 = fx.engine.fullBuild();
        Map<DocId, String> manifest3 = fx.versionStore.current().manifest();
        assertThat(manifest2).isEqualTo(manifest3);
        assertThat(v3.built()).isEmpty(); // everything still cached/up-to-date
    }

    @Test
    void incrementalWithNoChangesIsNoOp() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n");
        fx.engine.fullBuild();
        long versionBefore = fx.versionStore.current().versionId();

        BuildReport report = fx.engine.incrementalBuild();

        assertThat(report.success()).isTrue();
        assertThat(report.versionId()).isEqualTo(versionBefore);
        assertThat(report.built()).isEmpty();
        assertThat(report.affected()).isEmpty();
    }

    @Test
    void deletionInvalidatesAndRemovesProduct() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n");
        fx.write("b.md", "# B\n");
        fx.engine.fullBuild();

        fx.delete("b.md");
        BuildReport report = fx.engine.incrementalBuild();

        assertThat(report.success()).isTrue();
        assertThat(fx.versionStore.current().manifest()).containsKey(DocId.fromPath("a.md"));
        assertThat(fx.versionStore.current().manifest()).doesNotContainKey(DocId.fromPath("b.md"));
        assertThat(fx.versionStore.product(DocId.fromPath("b.md"))).isEmpty();
    }

    @Test
    void renameInvalidatesOldProductAndBuildsNew() {
        TestFixture fx = fixture();
        fx.write("old-name.md", "# Old\n");
        fx.engine.fullBuild();
        assertThat(fx.versionStore.product(DocId.fromPath("old-name.md"))).isPresent();

        fx.write("new-name.md", "# New\n");
        fx.delete("old-name.md");
        BuildReport report = fx.engine.incrementalBuild();

        assertThat(report.success()).isTrue();
        assertThat(fx.versionStore.product(DocId.fromPath("old-name.md"))).isEmpty();
        assertThat(fx.versionStore.product(DocId.fromPath("new-name.md"))).isPresent();
        assertThat(fx.versionStore.current().manifest()).doesNotContainKey(DocId.fromPath("old-name.md"));
    }

    @Test
    void deletionOfIncludedDocFailsBuildAndKeepsPublishedVersion() {
        TestFixture fx = fixture();
        fx.write("a.md", "@include b.md\n");
        fx.write("b.md", "# B\n");
        fx.engine.fullBuild();
        long publishedId = fx.versionStore.current().versionId();

        fx.delete("b.md");
        BuildReport report = fx.engine.incrementalBuild();

        assertThat(report.success()).isFalse();
        assertThat(report.errors()).anyMatch(e -> e.type().name().equals("MISSING_INCLUDE"));
        // Published version untouched
        assertThat(fx.versionStore.current().versionId()).isEqualTo(publishedId);
        assertThat(fx.versionStore.product(DocId.fromPath("a.md"))).isPresent();

        // Fix: restore b.md, incremental recovers
        fx.write("b.md", "# B restored\n");
        BuildReport recovered = fx.engine.incrementalBuild();
        assertThat(recovered.success()).isTrue();
        assertThat(fx.versionStore.product(DocId.fromPath("a.md")).orElseThrow().html())
                .contains("B restored");
    }

    @Test
    void missingIncludeFailsBuildWithoutPublishing() {
        TestFixture fx = fixture();
        fx.write("a.md", "@include missing.md\n");
        BuildReport report = fx.engine.fullBuild();
        assertThat(report.success()).isFalse();
        assertThat(report.errors()).anyMatch(e -> e.type().name().equals("MISSING_INCLUDE"));
        assertThat(fx.versionStore.hasCurrent()).isFalse();
    }

    @Test
    void includeCycleFailsBuild() {
        TestFixture fx = fixture();
        fx.write("a.md", "@include b.md\n");
        fx.write("b.md", "@include a.md\n");
        BuildReport report = fx.engine.fullBuild();
        assertThat(report.success()).isFalse();
        assertThat(report.errors()).anyMatch(e -> e.type().name().equals("INCLUDE_CYCLE"));
        assertThat(fx.versionStore.hasCurrent()).isFalse();
    }

    @Test
    void incrementalAfterFailureOnlyRebuildsAffectedRange() {
        TestFixture fx = fixture();
        fx.write("a.md", "# A\n");
        fx.write("b.md", "# B\n");
        fx.write("c.md", "# C\n");
        fx.engine.fullBuild();

        // Break a.md with a missing include
        fx.write("a.md", "@include gone.md\n");
        BuildReport failed = fx.engine.incrementalBuild();
        assertThat(failed.success()).isFalse();

        // Fix a.md; b and c are unaffected by a's content, but a must rebuild
        fx.write("a.md", "# A fixed\n");
        BuildReport recovered = fx.engine.incrementalBuild();
        assertThat(recovered.success()).isTrue();
        assertThat(recovered.built()).contains(DocId.fromPath("a.md"));
        assertThat(recovered.reused()).contains(DocId.fromPath("b.md"), DocId.fromPath("c.md"));
    }
}
