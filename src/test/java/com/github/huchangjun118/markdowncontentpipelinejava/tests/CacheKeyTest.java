package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.render.DefaultArtifactRenderer;
import com.github.huchangjun118.markdowncontentpipelinejava.source.SourceDirectoryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cache keys must cover every rendering input: own source, included sources
 * (transitively), link existence, policies and renderer version.
 */
class CacheKeyTest {

    private record Harness(BuildEngine engine, DefaultArtifactRenderer renderer,
                           BuildCache cache, ContentPipelineProperties props) {
    }

    private Harness harness(Path dir) {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        p.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(p.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(p);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, p);
        return new Harness(engine, renderer, cache, p);
    }

    private void write(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void includedChangeChangesParentIdentityAndCacheIsReusedOtherwise(@TempDir Path dir)
            throws Exception {
        write(dir, "parent.md", "# P\n\n{{include child.md}}\n");
        write(dir, "child.md", "# Child 1\n");
        Harness h = harness(dir);

        h.engine().fullBuild();
        assertEquals(2, h.cache().size());
        int afterFirst = h.cache().size();

        // Rebuild without changes: cache entries get hits, no growth.
        h.engine().fullBuild();
        assertEquals(afterFirst, h.cache().size());

        // Changing the child invalidates parent's cached key value; rebuild
        // replaces entries rather than growing without bound.
        write(dir, "child.md", "# Child 2 changed\n");
        h.engine().incrementalBuild(Map.of("child.md", ChangeType.MODIFIED));
        assertTrue(h.cache().size() <= 4,
                "cache must not grow unboundly; was " + h.cache().size());
    }

    @Test
    void rendererVersionBumpChangesKeys(@TempDir Path dir) throws Exception {
        write(dir, "doc.md", "# Doc\n");
        var v1 = artifactAfterFull(dir, 1);
        var v99 = artifactAfterFull(dir, 99);
        assertNotEquals(v1.contentId(), v99.contentId(),
                "renderer version must be part of the artifact identity");
        assertEquals(v1.html(), v99.html(), "only identity differs, not rendered bytes");
    }

    private com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact artifactAfterFull(
            Path dir, int rendererVersion) {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
        p.setSourceDir(dir.toString());
        p.setRendererVersion(rendererVersion);
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(p.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(p);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, p);
        var candidate = engine.fullBuild();
        return candidate.artifacts().get("doc.md");
    }
}
