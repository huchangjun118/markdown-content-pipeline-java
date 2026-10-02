package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
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
 * Verifies the central incremental contract:
 * <ol>
 *   <li>an incremental build produces exactly the artifacts a full build of
 *       the same sources would produce (same html, same content ids),</li>
 *   <li>unaffected documents keep their identity byte-for-byte,</li>
 *   <li>only the changed document and its downstream set are rebuilt,</li>
 *   <li>deletes and renames invalidate downstream content and caches.</li>
 * </ol>
 */
class IncrementalConsistencyTest {

    private record Harness(BuildCoordinator coordinator, Path dir) {
    }

    private Harness harness(Path dir, ContentPipelineProperties p) {
        p.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(p.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(p);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, p);
        VersionStore store = new VersionStore(p.getMaxVersions());
        return new Harness(new BuildCoordinator(engine, store, p), dir);
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

    private Map<String, BuildArtifact> currentArtifacts(BuildCoordinator c) {
        return new TreeMap<>(c.currentVersion().orElseThrow().artifacts());
    }

    /** Build the same source set in two coordinators: one incremental, one full. */
    private Harness independentFullHarness(Path dir) {
        return harness(dir, props());
    }

    @Test
    void incrementalEqualsFullAfterLeafChange(@TempDir Path dir) throws Exception {
        write(dir, "root.md", "# Root\n\n{{include a.md}}\n\n{{include b.md}}\n");
        write(dir, "a.md", "# A v1\n");
        write(dir, "b.md", "# B\n");
        write(dir, "lonely.md", "# Lonely\n");

        Harness h = harness(dir, props());
        h.coordinator().fullRebuild("initial");
        Map<String, BuildArtifact> before = currentArtifacts(h.coordinator());

        // Change a leaf include.
        write(dir, "a.md", "# A v2 - changed\n");
        var incremental = h.coordinator().incrementalRebuild(
                Map.of("a.md", ChangeType.MODIFIED), "inc-a");
        assertTrue(incremental.success(), () -> "errors: " + incremental.errors());
        Map<String, BuildArtifact> afterInc = currentArtifacts(h.coordinator());

        // Reference: a fresh coordinator builds the same tree fully.
        Harness full = independentFullHarness(dir);
        full.coordinator().fullRebuild("reference-full");
        Map<String, BuildArtifact> afterFull = currentArtifacts(full.coordinator());

        assertEquals(afterFull.keySet(), afterInc.keySet());
        for (String id : afterFull.keySet()) {
            BuildArtifact inc = afterInc.get(id);
            BuildArtifact ful = afterFull.get(id);
            assertEquals(ful.contentId(), inc.contentId(),
                    "contentId mismatch for " + id);
            assertEquals(ful.html(), inc.html(), "html mismatch for " + id);
        }

        // Root and a changed; b and lonely must keep the exact same identity.
        assertNotEquals(before.get("a.md").contentId(), afterInc.get("a.md").contentId());
        assertNotEquals(before.get("root.md").contentId(), afterInc.get("root.md").contentId());
        assertEquals(before.get("b.md").contentId(), afterInc.get("b.md").contentId());
        assertEquals(before.get("lonely.md").contentId(), afterInc.get("lonely.md").contentId());

        // Exactly two documents rebuilt, two reused.
        assertEquals(2, incremental.rebuiltDocuments());
        assertEquals(2, incremental.reusedArtifacts());
    }

    @Test
    void deletionRemovesArtifactAndRebuildsReferrers(@TempDir Path dir) throws Exception {
        write(dir, "root.md", "# Root\n\n{{include a.md}}\n");
        write(dir, "a.md", "# A\n");
        write(dir, "lonely.md", "# Lonely\n");

        Harness h = harness(dir, props());
        h.coordinator().fullRebuild("initial");
        var beforeLonely = h.coordinator().read("lonely.md").orElseThrow().contentId();

        Files.delete(dir.resolve("a.md"));
        var result = h.coordinator().incrementalRebuild(
                Map.of("a.md", ChangeType.DELETED), "delete-a");
        // root now has a missing include -> fatal under default policy, so no publish.
        assertFalse(result.success());
        // previous committed version remains available and unchanged.
        var version = h.coordinator().currentVersion().orElseThrow();
        assertTrue(version.artifacts().containsKey("a.md"),
                "last published version must stay intact after a failed rebuild");
        assertEquals(beforeLonely, version.artifact("lonely.md").contentId());

        // Fix root to no longer include the deleted file; now deletion publishes.
        write(dir, "root.md", "# Root fixed\n");
        var fixed = h.coordinator().incrementalRebuild(
                Map.of("root.md", ChangeType.MODIFIED), "fix-root");
        assertTrue(fixed.success(), () -> "errors: " + fixed.errors());
        var current = h.coordinator().currentVersion().orElseThrow();
        assertFalse(current.artifacts().containsKey("a.md"));
        assertTrue(current.artifacts().containsKey("lonely.md"));
        assertEquals(beforeLonely, current.artifact("lonely.md").contentId(),
                "unaffected document keeps identity across delete");
    }

    @Test
    void renameMovesArtifactAndInvalidatesEmbeddedContent(@TempDir Path dir) throws Exception {
        write(dir, "root.md", "# Root\n\n{{include old.md}}\n");
        write(dir, "old.md", "# Old name\n");

        Harness h = harness(dir, props());
        h.coordinator().fullRebuild("initial");
        String oldRootHtml = h.coordinator().read("root.md").orElseThrow().html();
        assertTrue(oldRootHtml.contains("Old name"));

        // Rename: delete old.md, add new.md and update the include.
        Files.delete(dir.resolve("old.md"));
        write(dir, "new.md", "# New name\n");
        write(dir, "root.md", "# Root\n\n{{include new.md}}\n");
        var result = h.coordinator().incrementalRebuild(Map.of(
                "old.md", ChangeType.DELETED,
                "new.md", ChangeType.ADDED,
                "root.md", ChangeType.MODIFIED), "rename");
        assertTrue(result.success(), () -> "errors: " + result.errors());
        var current = h.coordinator().currentVersion().orElseThrow();
        assertFalse(current.artifacts().containsKey("old.md"));
        assertTrue(current.artifacts().containsKey("new.md"));
        assertTrue(current.artifact("root.md").html().contains("New name"));
        assertFalse(current.artifact("root.md").html().contains("Old name"));

        // Matches a full build from scratch of the same files.
        Harness full = harness(dir, props());
        full.coordinator().fullRebuild("full-ref");
        var fullCurrent = full.coordinator().currentVersion().orElseThrow();
        assertEquals(fullCurrent.artifact("root.md").contentId(),
                current.artifact("root.md").contentId());
        assertEquals(fullCurrent.artifact("new.md").contentId(),
                current.artifact("new.md").contentId());
    }

    @Test
    void repeatedIdenticalBuildsKeepStableIdentities(@TempDir Path dir) throws Exception {
        write(dir, "root.md", "# Root\n\n{{include a.md}}\n");
        write(dir, "a.md", "# A\n");
        Harness h = harness(dir, props());
        var first = h.coordinator().fullRebuild("full-1");
        var second = h.coordinator().fullRebuild("full-2");
        assertTrue(second.success());
        var v1 = h.coordinator().readVersion(first.version()).orElseThrow();
        var v2 = h.coordinator().readVersion(second.version()).orElseThrow();
        for (String id : v1.artifacts().keySet()) {
            assertEquals(v1.artifact(id).contentId(), v2.artifact(id).contentId());
            assertEquals(v1.artifact(id).html(), v2.artifact(id).html());
        }
    }
}
