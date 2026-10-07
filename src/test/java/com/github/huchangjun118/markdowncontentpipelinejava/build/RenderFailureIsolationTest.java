package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single document that fails to build must not corrupt the published
 * version; the failure must be locatable and, once fixed, only the affected
 * range is rebuilt.
 */
class RenderFailureIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void renderFailureDoesNotPollutePublishedVersionAndRecoversIncrementally() {
        TestFixture fx = new TestFixture(tempDir);
        fx.write("a.md", "# A\n");
        fx.write("b.md", "@include a.md\n");
        fx.write("c.md", "# C\n");
        BuildReport v1 = fx.engine.fullBuild();
        assertThat(v1.success()).isTrue();
        long publishedId = fx.versionStore.current().versionId();
        Map<DocId, String> manifestV1 = fx.versionStore.current().manifest();

        // A builder that fails on a.md only.
        ContentBuilder failing = new ContentBuilder() {
            @Override
            public ContentProduct build(ParsedDocument doc, Map<DocId, ContentProduct> directIncludes,
                                        Function<String, DocId> includeResolver,
                                        Function<String, DocId> linkResolver,
                                        String contentHash, Set<DocId> dependencies) {
                if (doc.sourcePath().equals("a.md")) {
                    throw new IllegalStateException("boom");
                }
                return super.build(doc, directIncludes, includeResolver, linkResolver,
                        contentHash, dependencies);
            }
        };
        BuildEngine failingEngine = new BuildEngine(fx.scanner, fx.parser, fx.graphBuilder,
                fx.hasher, failing, fx.cache, fx.versionStore);

        fx.write("a.md", "# A changed\n");
        BuildReport failed = failingEngine.incrementalBuild();

        assertThat(failed.success()).isFalse();
        // The failure is locatable: type + source path of the broken document.
        assertThat(failed.errors()).anyMatch(e ->
                e.type().name().equals("RENDER_ERROR") && e.sourcePath().equals("a.md"));
        // The published version is untouched.
        assertThat(fx.versionStore.current().versionId()).isEqualTo(publishedId);
        assertThat(fx.versionStore.current().manifest()).isEqualTo(manifestV1);
        assertThat(fx.versionStore.product(DocId.fromPath("a.md")).orElseThrow().html())
                .contains("A");

        // Fix the document; the normal engine rebuilds only the affected range.
        fx.write("a.md", "# A fixed\n");
        BuildReport recovered = fx.engine.incrementalBuild();
        assertThat(recovered.success()).isTrue();
        assertThat(recovered.built()).containsExactlyInAnyOrder(
                DocId.fromPath("a.md"), DocId.fromPath("b.md"));
        assertThat(recovered.reused()).contains(DocId.fromPath("c.md"));
        assertThat(fx.versionStore.product(DocId.fromPath("b.md")).orElseThrow().html())
                .contains("A fixed");
    }
}
