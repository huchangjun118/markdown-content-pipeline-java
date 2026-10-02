package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.CyclePolicy;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.DefaultArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deep linear include chains and self-cycles must terminate deterministically
 * regardless of JVM stack size (graph traversal is iterative).
 */
class DeepIncludeTest {

    private BuildCoordinator coordinator(Path dir, ContentPipelineProperties p) {
        p.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(p.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(p);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, p);
        VersionStore store = new VersionStore(p.getMaxVersions());
        return new BuildCoordinator(engine, store, p);
    }

    @Test
    void deepLinearChainRendersAndIsBoundedByDepthLimit(@TempDir Path dir) throws Exception {
        int n = 200;
        for (int i = 0; i < n; i++) {
            String body;
            if (i == 0) {
                body = "# d0\n";
            } else {
                body = "# d" + i + "\n\n{{include d" + (i - 1) + ".md}}\n";
            }
            Files.writeString(dir.resolve("d" + i + ".md"), body);
        }

        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        p.setMaxIncludeDepth(16);
        BuildCoordinator c = coordinator(dir, p);
        var result = c.fullRebuild("deep");
        assertTrue(result.success(), () -> "errors: " + result.errors());
        String html = c.read("d" + (n - 1) + ".md").orElseThrow().html();
        assertTrue(html.contains("include-depth-limit"),
                "chain past the depth limit must be marked, not expanded forever");
        // The head of the chain is unaffected by the deep tail marker.
        assertTrue(c.read("d0.md").orElseThrow().html().contains("<h1>d0</h1>"));
    }

    @Test
    void selfIncludeIsDetectedAsCycle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("self.md"), "# self\n\n{{include self.md}}\n");
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        BuildCoordinator failing = coordinator(dir, p);
        var result = failing.fullRebuild("self-cycle");
        assertFalse(result.success());

        p.setCyclePolicy(CyclePolicy.BREAK);
        BuildCoordinator breaking = coordinator(dir, p);
        var broken = breaking.fullRebuild("self-cycle-break");
        assertTrue(broken.success(), () -> "errors: " + broken.errors());
        assertTrue(breaking.read("self.md").orElseThrow().html().contains("include-cycle"));
    }
}
