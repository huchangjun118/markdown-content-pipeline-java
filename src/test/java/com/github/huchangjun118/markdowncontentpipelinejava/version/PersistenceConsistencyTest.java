package com.github.huchangjun118.markdowncontentpipelinejava.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.markdowncontentpipelinejava.build.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.build.ContentBuilder;
import com.github.huchangjun118.markdowncontentpipelinejava.build.ProductHasher;
import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.dep.DependencyGraphBuilder;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.parse.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.tests.TestFixture;
import com.github.huchangjun118.markdowncontentpipelinejava.watch.ChangeScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference-existence changes must remain consistent after the store is
 * reloaded from disk: historical versions must keep resolving the exact
 * products they were published with, and an incremental build performed by a
 * freshly loaded engine must equal a full build.
 */
class PersistenceConsistencyTest {

    @TempDir
    Path tempDir;

    private BuildEngine reloadedEngine(TestFixture fx) {
        PipelineProperties props = new PipelineProperties();
        props.setSourceDir(fx.sourceDir.toString());
        props.setOutputDir(fx.outputDir.toString());
        props.setMissingRefPolicy(PipelineProperties.MissingRefPolicy.WARN);
        ChangeScanner scanner = new ChangeScanner(props);
        MarkdownParser parser = new MarkdownParser();
        DependencyGraphBuilder graphBuilder = new DependencyGraphBuilder(props);
        ProductHasher hasher = new ProductHasher();
        ContentBuilder contentBuilder = new ContentBuilder();
        BuildCache cache = new BuildCache(props.getCacheMaxEntries());
        VersionStore store = new VersionStore(props, new ObjectMapper());
        store.load();
        return new BuildEngine(scanner, parser, graphBuilder, hasher,
                contentBuilder, cache, store);
    }

    private VersionStore reloadedStore(TestFixture fx) {
        PipelineProperties props = new PipelineProperties();
        props.setSourceDir(fx.sourceDir.toString());
        props.setOutputDir(fx.outputDir.toString());
        props.setMissingRefPolicy(PipelineProperties.MissingRefPolicy.WARN);
        VersionStore store = new VersionStore(props, new ObjectMapper());
        store.load();
        return store;
    }

    @Test
    void historicalProductsSurviveRestartAndReferenceChurn() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# Guide\n");
        fx.engine.fullBuild();
        long v1 = fx.versionStore.current().versionId();
        String v1Hash = fx.versionStore.current().manifest().get(DocId.fromPath("index.md"));
        String v1Html = fx.versionStore.product(DocId.fromPath("index.md")).orElseThrow().html();

        fx.delete("guide.md");
        fx.engine.incrementalBuild();
        fx.write("guide.md", "# Guide 2\n");
        fx.engine.incrementalBuild();

        VersionStore reloaded = reloadedStore(fx);
        assertThat(reloaded.version(v1).orElseThrow().manifest().get(DocId.fromPath("index.md")))
                .isEqualTo(v1Hash);
        ContentProduct historical = reloaded.productByHash(v1Hash).orElseThrow();
        assertThat(historical.html()).isEqualTo(v1Html);

        reloaded.rollbackTo(v1);
        assertThat(reloaded.product(DocId.fromPath("index.md")).orElseThrow().html())
                .isEqualTo(v1Html);
    }

    @Test
    void incrementalAfterRestartMatchesFullAfterReferenceChange() {
        TestFixture fx = new TestFixture(tempDir, PipelineProperties.MissingRefPolicy.WARN);
        fx.write("index.md", "# Home\n[x](guide.md)\n");
        fx.write("guide.md", "# Guide\n");
        fx.engine.fullBuild();

        // Restart with a pending deletion on disk, then build incrementally.
        fx.delete("guide.md");
        BuildEngine engine2 = reloadedEngine(fx);
        BuildReport inc = engine2.incrementalBuild();
        assertThat(inc.success()).isTrue();

        // Re-read the current manifest from a store instance observing the same files.
        VersionStore storeForRead = reloadedStore(fx);
        Map<DocId, String> incManifest = storeForRead.version(inc.versionId()).orElseThrow().manifest();

        BuildEngine engine3 = reloadedEngine(fx);
        BuildReport full = engine3.fullBuild();
        VersionStore storeAfterFull = reloadedStore(fx);
        assertThat(storeAfterFull.version(full.versionId()).orElseThrow().manifest())
                .isEqualTo(incManifest);
    }
}
