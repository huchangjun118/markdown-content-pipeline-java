package com.github.huchangjun118.markdowncontentpipelinejava.config;

import com.github.huchangjun118.markdowncontentpipelinejava.model.CyclePolicy;
import com.github.huchangjun118.markdowncontentpipelinejava.model.MissingRefPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the content pipeline, prefix {@code content.pipeline}.
 */
@ConfigurationProperties(prefix = "content.pipeline")
public class ContentPipelineProperties {

    /** Root directory of markdown sources. */
    private String sourceDir = "content";
    /** File name suffix considered a source document. */
    private String sourceSuffix = ".md";
    /** Policy when an include cycle is detected. */
    private CyclePolicy cyclePolicy = CyclePolicy.FAIL;
    /** Policy when a referenced target does not exist. */
    private MissingRefPolicy missingRefPolicy = MissingRefPolicy.FAIL;
    /** Maximum include expansion depth under the BOUNDED cycle policy. */
    private int maxIncludeDepth = 16;
    /** Number of published versions (including current) kept for query and rollback. */
    private int maxVersions = 10;
    /** Maximum number of cached document artifacts (LRU). */
    private int cacheSize = 1024;
    /** Bump to invalidate every cache entry after renderer changes. */
    private int rendererVersion = 1;
    /** Run a full build synchronously during application startup. */
    private boolean buildOnStartup = true;

    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }
    public String getSourceSuffix() { return sourceSuffix; }
    public void setSourceSuffix(String sourceSuffix) { this.sourceSuffix = sourceSuffix; }
    public CyclePolicy getCyclePolicy() { return cyclePolicy; }
    public void setCyclePolicy(CyclePolicy cyclePolicy) { this.cyclePolicy = cyclePolicy; }
    public MissingRefPolicy getMissingRefPolicy() { return missingRefPolicy; }
    public void setMissingRefPolicy(MissingRefPolicy missingRefPolicy) { this.missingRefPolicy = missingRefPolicy; }
    public int getMaxIncludeDepth() { return maxIncludeDepth; }
    public void setMaxIncludeDepth(int maxIncludeDepth) { this.maxIncludeDepth = maxIncludeDepth; }
    public int getMaxVersions() { return maxVersions; }
    public void setMaxVersions(int maxVersions) { this.maxVersions = maxVersions; }
    public int getCacheSize() { return cacheSize; }
    public void setCacheSize(int cacheSize) { this.cacheSize = cacheSize; }
    public int getRendererVersion() { return rendererVersion; }
    public void setRendererVersion(int rendererVersion) { this.rendererVersion = rendererVersion; }
    public boolean isBuildOnStartup() { return buildOnStartup; }
    public void setBuildOnStartup(boolean buildOnStartup) { this.buildOnStartup = buildOnStartup; }
}
