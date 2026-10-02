package com.github.huchangjun118.markdowncontentpipelinejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the content pipeline.
 */
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /** Root directory containing the Markdown source documents. */
    private String sourceDir = "data/sources";

    /** Directory where built products and versions are persisted. */
    private String outputDir = "data/output";

    /** Maximum number of products kept in the in-memory build cache. */
    private int cacheMaxEntries = 1000;

    /** Maximum number of published versions kept in history. */
    private int versionRetention = 30;

    /** How to handle references that point to non-existent documents. */
    private MissingRefPolicy missingRefPolicy = MissingRefPolicy.ERROR;

    /** How to handle include cycles. Always a hard error. */
    private IncludeCyclePolicy includeCyclePolicy = IncludeCyclePolicy.ERROR;

    public enum MissingRefPolicy {
        ERROR, WARN
    }

    public enum IncludeCyclePolicy {
        ERROR
    }

    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }

    public int getVersionRetention() { return versionRetention; }
    public void setVersionRetention(int versionRetention) { this.versionRetention = versionRetention; }

    public MissingRefPolicy getMissingRefPolicy() { return missingRefPolicy; }
    public void setMissingRefPolicy(MissingRefPolicy missingRefPolicy) { this.missingRefPolicy = missingRefPolicy; }

    public IncludeCyclePolicy getIncludeCyclePolicy() { return includeCyclePolicy; }
    public void setIncludeCyclePolicy(IncludeCyclePolicy includeCyclePolicy) { this.includeCyclePolicy = includeCyclePolicy; }
}
