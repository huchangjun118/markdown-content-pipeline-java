package com.github.huchangjun118.markdowncontentpipelinejava.coordinator;

import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.engine.CandidateBuild;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildResult;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.VersionInfo;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single entry point for builds and reads.
 *
 * <p>Builds (full, incremental, rollback) are serialized by an internal
 * monitor so two builds can never assemble against each other's intermediate
 * state. Reads take no build lock and simply dereference the
 * {@link VersionStore}'s atomically published current version, so:
 * <ul>
 *   <li>readers always observe one complete committed version, never a mix of
 *       old and new artifacts;</li>
 *   <li>readers never block a build and a build never blocks readers.</li>
 * </ul>
 *
 * <p>A candidate without fatal errors is committed as a new immutable version.
 * A candidate that contains fatal errors is <em>not published</em>: the
 * previous version stays current, readers observe no change at all, and the
 * located errors are returned to the caller. Non-fatal warnings never block
 * publication. This keeps a single broken document from polluting any
 * published version while still allowing its failure to be located.
 */
@Component
public class BuildCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BuildCoordinator.class);

    private final BuildEngine engine;
    private final VersionStore versions;
    private final ContentPipelineProperties properties;
    private final Object buildLock = new Object();

    public BuildCoordinator(BuildEngine engine, VersionStore versions,
                            ContentPipelineProperties properties) {
        this.engine = engine;
        this.versions = versions;
        this.properties = properties;
    }

    public BuildResult fullRebuild(String trigger) {
        synchronized (buildLock) {
            CandidateBuild candidate = engine.fullBuild();
            return commit(candidate, trigger, true);
        }
    }

    public BuildResult incrementalRebuild(Map<String, ChangeType> changes, String trigger) {
        synchronized (buildLock) {
            CandidateBuild candidate = engine.incrementalBuild(
                    changes == null ? Map.of() : changes);
            return commit(candidate, trigger, false);
        }
    }

    public Optional<ContentVersion> currentVersion() {
        return versions.current();
    }

    /** Read one document from the currently published version. Never blocks builds. */
    public Optional<BuildArtifact> read(String documentId) {
        return versions.current().map(v -> v.artifact(documentId));
    }

    public Optional<ContentVersion> readVersion(long version) {
        return versions.get(version);
    }

    public List<VersionInfo> listVersions() {
        return versions.list();
    }

    public Optional<ContentVersion> rollback(long targetVersion, String trigger) {
        synchronized (buildLock) {
            Optional<ContentVersion> result = versions.rollback(targetVersion, trigger);
            result.ifPresentOrElse(
                    v -> log.info("rolled back to version {} as new version {}",
                            targetVersion, v.version()),
                    () -> log.warn("rollback target version {} not found in history",
                            targetVersion));
            return result;
        }
    }

    private BuildResult commit(CandidateBuild candidate, String trigger, boolean full) {
        boolean hasFatal = candidate.errors().stream().anyMatch(BuildError::fatal);
        if (hasFatal) {
            long current = versions.current().map(ContentVersion::version).orElse(0L);
            log.warn("build of trigger '{}' produced fatal errors; "
                    + "keeping current version {}", trigger, current);
            return new BuildResult(
                    current, false, full, candidate.changedCount(),
                    candidate.rebuiltCount(), candidate.reusedCount(),
                    candidate.errors());
        }
        ContentVersion published = versions.publish(candidate, trigger);
        return new BuildResult(
                published.version(), true, full, candidate.changedCount(),
                candidate.rebuiltCount(), candidate.reusedCount(),
                candidate.errors());
    }
}
