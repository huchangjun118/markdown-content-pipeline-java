package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.markdowncontentpipelinejava.build.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.build.ContentBuilder;
import com.github.huchangjun118.markdowncontentpipelinejava.build.ProductHasher;
import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.dep.DependencyGraphBuilder;
import com.github.huchangjun118.markdowncontentpipelinejava.parse.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import com.github.huchangjun118.markdowncontentpipelinejava.watch.ChangeScanner;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires the pipeline components against temporary source/output directories.
 */
public class TestFixture {

    public final Path sourceDir;
    public final Path outputDir;
    public final PipelineProperties properties;
    public final ChangeScanner scanner;
    public final MarkdownParser parser;
    public final DependencyGraphBuilder graphBuilder;
    public final ProductHasher hasher;
    public final ContentBuilder contentBuilder;
    public final BuildCache cache;
    public final VersionStore versionStore;
    public final BuildEngine engine;

    public TestFixture(@TempDir Path tempDir) {
        this(tempDir, PipelineProperties.MissingRefPolicy.ERROR);
    }

    public TestFixture(Path tempDir, PipelineProperties.MissingRefPolicy missingRefPolicy) {
        this(tempDir, missingRefPolicy, 1000);
    }

    public TestFixture(Path tempDir, PipelineProperties.MissingRefPolicy missingRefPolicy, int cacheMaxEntries) {
        this.sourceDir = tempDir.resolve("sources");
        this.outputDir = tempDir.resolve("output");
        try {
            Files.createDirectories(sourceDir);
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        properties = new PipelineProperties();
        properties.setSourceDir(sourceDir.toString());
        properties.setOutputDir(outputDir.toString());
        properties.setMissingRefPolicy(missingRefPolicy);
        properties.setCacheMaxEntries(cacheMaxEntries);

        scanner = new ChangeScanner(properties);
        parser = new MarkdownParser();
        graphBuilder = new DependencyGraphBuilder(properties);
        hasher = new ProductHasher();
        contentBuilder = new ContentBuilder();
        cache = new BuildCache(properties.getCacheMaxEntries());
        versionStore = new VersionStore(properties, new ObjectMapper());
        versionStore.load();
        engine = new BuildEngine(scanner, parser, graphBuilder, hasher, contentBuilder, cache, versionStore);
    }

    public void write(String relativePath, String content) {
        try {
            Path f = sourceDir.resolve(relativePath);
            Files.createDirectories(f.getParent());
            Files.writeString(f, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(sourceDir.resolve(relativePath));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
