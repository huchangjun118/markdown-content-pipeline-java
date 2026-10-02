package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.graph.DependencyResolver;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildResult;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.CyclePolicy;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ErrorKind;
import com.github.huchangjun118.markdowncontentpipelinejava.model.MissingRefPolicy;
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

/** Cycle and missing-reference policy tests, including error location. */
class ReferenceFailureTest {

    private record Harness(BuildCoordinator coordinator, Path dir, ContentPipelineProperties props) {
    }

    private Harness harness(Path dir, ContentPipelineProperties props) {
        props.setSourceDir(dir.toString());
        SourceDirectoryScanner scanner = new SourceDirectoryScanner();
        MarkdownParser parser = new MarkdownParser();
        DependencyResolver resolver = new DependencyResolver();
        BuildCache cache = new BuildCache(props.getCacheSize());
        DefaultArtifactRenderer renderer = new DefaultArtifactRenderer(props);
        BuildEngine engine = new BuildEngine(scanner, parser, resolver, renderer, cache, props);
        VersionStore store = new VersionStore(props.getMaxVersions());
        BuildCoordinator coordinator = new BuildCoordinator(engine, store, props);
        return new Harness(coordinator, dir, props);
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

    private BuildError fatalOfKind(BuildResult result, ErrorKind kind) {
        return result.errors().stream()
                .filter(e -> e.fatal() && e.kind() == kind)
                .findFirst().orElse(null);
    }

    @Test
    void includeCycleFailsByDefaultAndIsLocated(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\n{{include b.md}}\n");
        write(dir, "b.md", "# B\n\n{{include a.md}}\n");
        write(dir, "ok.md", "# OK\n");

        BuildCoordinator coordinator = harness(dir, props()).coordinator();
        BuildResult result = coordinator.fullRebuild("cycle");

        assertFalse(result.success());
        assertNotNull(fatalOfKind(result, ErrorKind.CYCLIC_REFERENCE));
        // Every cycle participant is reported.
        long cyclicDocs = result.errors().stream()
                .filter(e -> e.kind() == ErrorKind.CYCLIC_REFERENCE && e.fatal())
                .map(BuildError::documentId).distinct().count();
        assertTrue(cyclicDocs >= 2);
        // Line numbers locate the include directive (line 3).
        assertTrue(result.errors().stream()
                .anyMatch(e -> e.kind() == ErrorKind.CYCLIC_REFERENCE && e.line() == 3));
        // Unrelated documents are still not published... actually build fails as
        // a whole; no version must be published, so reads see nothing.
        assertTrue(coordinator.currentVersion().isEmpty());
    }

    @Test
    void cycleBreakPolicyPublishesWithMarkers(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\n{{include b.md}}\n");
        write(dir, "b.md", "# B\n\n{{include a.md}}\n");

        ContentPipelineProperties p = props();
        p.setCyclePolicy(CyclePolicy.BREAK);
        BuildCoordinator coordinator = harness(dir, p).coordinator();
        BuildResult result = coordinator.fullRebuild("cycle-break");

        assertTrue(result.success(), () -> "errors: " + result.errors());
        var artifact = coordinator.read("a.md").orElseThrow();
        assertTrue(artifact.html().contains("include-cycle")
                || artifact.html().contains("cyclic include"));
        // Warnings remain visible and located.
        assertTrue(result.errors().stream().anyMatch(e ->
                e.kind() == ErrorKind.CYCLIC_REFERENCE && !e.fatal() && e.line() == 3));
    }

    @Test
    void missingIncludeFailsByDefault(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\ntext\n\n{{include ghost.md}}\n");
        BuildCoordinator coordinator = harness(dir, props()).coordinator();
        BuildResult result = coordinator.fullRebuild("missing");

        assertFalse(result.success());
        BuildError error = fatalOfKind(result, ErrorKind.MISSING_REFERENCE);
        assertNotNull(error);
        assertEquals("a.md", error.documentId());
        assertTrue(error.message().contains("ghost.md"));
        assertEquals(5, error.line(), "missing include is on line 5");
        assertTrue(coordinator.currentVersion().isEmpty(),
                "failed build must not publish anything");
    }

    @Test
    void missingIncludePlaceholderPublishesLocatedWarning(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\n{{include ghost.md}}\n");
        ContentPipelineProperties p = props();
        p.setMissingRefPolicy(MissingRefPolicy.PLACEHOLDER);
        BuildCoordinator coordinator = harness(dir, p).coordinator();
        BuildResult result = coordinator.fullRebuild("missing-placeholder");

        assertTrue(result.success(), () -> "errors: " + result.errors());
        var artifact = coordinator.read("a.md").orElseThrow();
        assertTrue(artifact.html().contains("missing include"));
        assertTrue(artifact.html().contains("ghost.md"));
        assertTrue(result.errors().stream().anyMatch(e ->
                e.kind() == ErrorKind.MISSING_REFERENCE && !e.fatal()
                        && e.documentId().equals("a.md") && e.line() == 3));
    }

    @Test
    void missingLinkFailsByDefaultButPlaceholderKeepsLink(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\nsee [other](nowhere.md) please\n");

        BuildCoordinator failing = harness(dir, props()).coordinator();
        BuildResult failResult = failing.fullRebuild("missing-link");
        assertFalse(failResult.success());
        assertNotNull(fatalOfKind(failResult, ErrorKind.MISSING_REFERENCE));

        ContentPipelineProperties p = props();
        p.setMissingRefPolicy(MissingRefPolicy.PLACEHOLDER);
        BuildCoordinator ok = harness(dir, p).coordinator();
        BuildResult okResult = ok.fullRebuild("missing-link-placeholder");
        assertTrue(okResult.success(), () -> "errors: " + okResult.errors());
        String html = ok.read("a.md").orElseThrow().html();
        assertTrue(html.contains("nowhere.md"));
    }

    @Test
    void fixingBrokenDocumentRebuildsOnlyAffectedRange(@TempDir Path dir) throws Exception {
        write(dir, "a.md", "# A\n\n{{include b.md}}\n");
        write(dir, "b.md", "# B\n\n{{include ghost.md}}\n");
        write(dir, "c.md", "# C\n");

        BuildCoordinator coordinator = harness(dir, props()).coordinator();
        BuildResult broken = coordinator.fullRebuild("broken");
        assertFalse(broken.success());
        assertTrue(coordinator.currentVersion().isEmpty());

        // Fix b.md by creating the missing target.
        write(dir, "ghost.md", "# Ghost\n");
        BuildResult repaired = coordinator.incrementalRebuild(
                Map.of("ghost.md", ChangeType.ADDED), "repair");
        assertTrue(repaired.success(), () -> "errors: " + repaired.errors());
        assertEquals(4, repaired.rebuiltDocuments() + repaired.reusedArtifacts());
        assertNotNull(coordinator.read("a.md").orElse(null));
        assertNotNull(coordinator.read("b.md").orElse(null));
        // c.md is unrelated and its identity must be stable.
        var firstC = coordinator.read("c.md").orElseThrow().contentId();
        BuildResult again = coordinator.incrementalRebuild(
                Map.of("b.md", ChangeType.MODIFIED), "noop-check");
        var secondC = coordinator.read("c.md").orElseThrow().contentId();
        assertEquals(firstC, secondC, "unrelated artifact identity must not change");
    }
}
