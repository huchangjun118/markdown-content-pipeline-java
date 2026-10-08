package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
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
 * A single document that throws while rendering must fail the build with a
 * locatable RENDER_ERROR, leave the last published version untouched and
 * cached, and after the defect is fixed only the affected range rebuilds.
 */
class RenderFailureIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void renderErrorIsLocatableAndDoesNotPublish() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("a.md", "# A\n[x](b.md)\n");
        fx.write("b.md", "# B\n");
        fx.write("c.md", "# C\n");
        fx.engine.fullBuild();
        long publishedId = fx.versionStore.current().versionId();

        // Engine whose builder blows up only for document a.
        ContentBuilder failingBuilder = new ContentBuilder() {
            @Override
            public ContentProduct build(ParsedDocument doc, Map<DocId, ContentProduct> directIncludes,
                                        Function<String, DocId> includeResolver,
                                        Function<String, DocId> linkResolver,
                                        String contentHash, Set<DocId> dependencies) {
                if (doc.id().equals(DocId.fromPath("a.md"))) {
                    throw new IllegalStateException("simulated renderer explosion");
                }
                return super.build(doc, directIncludes, includeResolver, linkResolver, contentHash, dependencies);
            }
        };
        BuildEngine broken = new BuildEngine(fx.scanner, fx.parser, fx.graphBuilder, fx.hasher,
                failingBuilder, fx.cache, fx.versionStore);

        // Force a real re-render (not a cache hit) by changing a, then run the
        // broken engine incrementally so it must render document a.
        fx.write("a.md", "# A about to break\n[x](b.md)\n");
        var failed = broken.incrementalBuild();
        assertThat(failed.success()).isFalse();
        assertThat(failed.errors()).hasSize(1);
        assertThat(failed.errors().get(0).type().name()).isEqualTo("RENDER_ERROR");
        assertThat(failed.errors().get(0).sourcePath()).isEqualTo("a.md");
        assertThat(failed.errors().get(0).message()).contains("simulated renderer explosion");
        assertThat(fx.versionStore.current().versionId()).isEqualTo(publishedId);
        // Published version still serves all products.
        assertThat(fx.versionStore.product(DocId.fromPath("a.md"))).isPresent();
        assertThat(fx.versionStore.product(DocId.fromPath("b.md"))).isPresent();

        // Recover with a healthy engine and force a change so a rebuild happens:
        // only a is new content; b and c stay reusable.
        fx.write("a.md", "# A recovered\n[x](b.md)\n");
        var recovered = fx.engine.incrementalBuild();
        assertThat(recovered.success()).isTrue();
        assertThat(recovered.built()).contains(DocId.fromPath("a.md"));
        assertThat(recovered.reused()).contains(DocId.fromPath("b.md"), DocId.fromPath("c.md"));
    }
}
