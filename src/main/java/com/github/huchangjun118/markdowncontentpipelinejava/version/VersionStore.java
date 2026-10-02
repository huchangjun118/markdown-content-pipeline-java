package com.github.huchangjun118.markdowncontentpipelinejava.version;

import com.github.huchangjun118.markdowncontentpipelinejava.engine.CandidateBuild;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.VersionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores immutable published versions.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>Reads ({@link #current()}, {@link #get(long)}) are lock-free and see
 *       only complete committed versions because the whole immutable state is
 *       swapped in one atomic reference publication.</li>
 *   <li>Publish and rollback are serialized on the store's monitor, so when
 *       they happen concurrently their outcome is simply the order in which
 *       the monitor hands them over; version numbers are always monotonic and
 *       never reused.</li>
 *   <li>Rollback does not mutate history: it publishes a <em>copy</em> of an
 *       earlier version as a brand new version. Historical content therefore
 *       stays queryable forever (until bounded out) and reads after rollback
 *       are byte-identical to the original historical version, because the
 *       immutable artifact objects are shared.</li>
 * </ul>
 *
 * <p>History is bounded to {@code maxVersions} most recently published
 * versions; the current version is always retained.
 */
public class VersionStore {

    private record State(long nextVersion, List<ContentVersion> history, long currentVersion) {
    }

    private final int maxVersions;
    private final AtomicReference<State> state = new AtomicReference<>(new State(1, List.of(), 0));

    public VersionStore(int maxVersions) {
        this.maxVersions = Math.max(1, maxVersions);
    }

    /** Atomically publish a candidate as the next version. */
    public synchronized ContentVersion publish(CandidateBuild candidate, String trigger) {
        State current = state.get();
        long number = current.nextVersion();
        ContentVersion version = new ContentVersion(
                number,
                candidate.artifacts(),
                candidate.errors(),
                trigger == null ? "build" : trigger,
                System.currentTimeMillis());
        commit(current, version);
        return version;
    }

    public Optional<ContentVersion> current() {
        State snapshot = state.get();
        if (snapshot.history().isEmpty()) {
            return Optional.empty();
        }
        return find(snapshot.history(), snapshot.currentVersion());
    }

    public Optional<ContentVersion> get(long version) {
        State snapshot = state.get();
        return find(snapshot.history(), version);
    }

    public List<VersionInfo> list() {
        State snapshot = state.get();
        List<VersionInfo> infos = new ArrayList<>();
        for (ContentVersion v : snapshot.history()) {
            infos.add(new VersionInfo(v.version(), v.artifacts().size(), v.errors(),
                    v.trigger(), v.createdAt(), v.version() == snapshot.currentVersion()));
        }
        return infos;
    }

    /**
     * Atomically publish a copy of an existing version as the next version.
     *
     * @return empty when the target version has aged out of the bounded history
     */
    public synchronized Optional<ContentVersion> rollback(long targetVersion, String trigger) {
        State current = state.get();
        Optional<ContentVersion> target = find(current.history(), targetVersion);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        ContentVersion original = target.get();
        long number = current.nextVersion();
        ContentVersion copy = new ContentVersion(
                number,
                original.artifacts(),
                original.errors(),
                trigger == null ? ("rollback:" + targetVersion) : trigger,
                System.currentTimeMillis());
        commit(current, copy);
        return Optional.of(copy);
    }

    private void commit(State current, ContentVersion version) {
        List<ContentVersion> history = new ArrayList<>(current.history());
        history.add(version);
        // Keep the newest maxVersions entries; the just-published version is
        // always among them and becomes current.
        while (history.size() > maxVersions) {
            history.remove(0);
        }
        state.set(new State(current.nextVersion() + 1, List.copyOf(history), version.version()));
    }

    private static Optional<ContentVersion> find(List<ContentVersion> history, long version) {
        // Lists are small and newest-last; a linear scan avoids any map state.
        for (int i = history.size() - 1; i >= 0; i--) {
            ContentVersion candidate = history.get(i);
            if (candidate.version() == version) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
