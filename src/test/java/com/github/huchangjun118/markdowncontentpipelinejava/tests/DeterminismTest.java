package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same inputs must produce byte-identical artifacts and identities across
 * independent builds, independent coordinators and (by construction of the
 * sorted topological order) any execution scheduling.
 */
class DeterminismTest {

    private BuildCoordinator coordinator(Path dir) {
        ContentPipelineProperties p = new ContentPipelineProperties();
        p.setBuildOnStartup(false);
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

    private void write(Path dir, String id, String content) throws IOException {
        Path file = dir.resolve(id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Map<String, BuildArtifact> buildAll(Path dir) {
        BuildCoordinator c = coordinator(dir);
        c.fullRebuild("determinism");
        return new TreeMap<>(c.currentVersion().orElseThrow().artifacts());
    }

    @Test
    void independentBuildsOfSameTreeAreIdentical(@TempDir Path a, @TempDir Path b) throws Exception {
        String[] files = {"z.md", "a.md", "m/nested.md", "root.md", "leaf.md"};
        String[] contents = {
                "# Zed\n",
                "# A\n\nsee [root](root.md)\n",
                "# Nested\n\n{{include ../leaf.md}}\n",
                "# Root\n\n{{include a.md}}\n\n{{include z.md}}\n\n{{include m/nested.md}}\n",
                "# Leaf body\n"
        };
        for (int i = 0; i < files.length; i++) {
            write(a, files[i], contents[i]);
            write(b, files[i], contents[i]);
        }

        Map<String, BuildArtifact> first = buildAll(a);
        // Build twice more in fresh coordinators/caches.
        Map<String, BuildArtifact> second = buildAll(b);
        Map<String, BuildArtifact> third = buildAll(a);

        assertEquals(first.keySet(), second.keySet());
        for (String id : first.keySet()) {
            assertEquals(first.get(id).contentId(), second.get(id).contentId(),
                    "identity differs for " + id);
            assertEquals(first.get(id).html(), second.get(id).html(),
                    "html differs for " + id);
            assertEquals(first.get(id).title(), second.get(id).title());
            assertEquals(first.get(id).contentId(), third.get(id).contentId());
            assertEquals(first.get(id).html(), third.get(id).html());
        }
    }

    @Test
    void unrelatedOrderingChangesDoNotAffectIdentity(@TempDir Path dir) throws Exception {
        write(dir, "root.md", "# Root\n\n{{include leaf.md}}\n");
        write(dir, "leaf.md", "# Leaf\n");
        Map<String, BuildArtifact> before = buildAll(dir);

        // Add a completely unrelated document; existing identities stay stable.
        write(dir, "other.md", "# Other\n");
        Map<String, BuildArtifact> after = buildAll(dir);
        assertEquals(before.get("root.md").contentId(), after.get("root.md").contentId());
        assertEquals(before.get("leaf.md").contentId(), after.get("leaf.md").contentId());
    }
}
