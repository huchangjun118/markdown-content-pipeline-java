package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.DefaultArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** First end-to-end smoke test driving the coordinator against a temp dir. */
class SmokePipelineTest {

    private BuildCoordinator newCoordinator(Path dir, ContentPipelineProperties props) {
        props.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(props.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(props);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, props);
        VersionStore store = new VersionStore(props.getMaxVersions());
        return new BuildCoordinator(engine, store, props);
    }

    private ContentPipelineProperties props() {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        return p;
    }

    private void write(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void fullBuildRendersIncludes(@TempDir Path dir) throws Exception {
        write(dir, "index.md", "# Hello\n\nIntro.\n\n{{include parts/child.md}}\n");
        write(dir, "parts/child.md", "## Child\n\nchild body\n");

        BuildCoordinator coordinator = newCoordinator(dir, props());
        var result = coordinator.fullRebuild("test");
        assertTrue(result.success(), () -> "errors: " + result.errors());

        BuildArtifact index = coordinator.read("index.md").orElseThrow();
        assertTrue(index.html().contains("Hello"));
        assertTrue(index.html().contains("Child"), "include must be expanded:\n" + index.html());
        assertTrue(index.html().contains("child body"));
    }
}
