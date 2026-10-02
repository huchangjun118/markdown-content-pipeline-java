package com.github.huchangjun118.markdowncontentpipelinejava.cache;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded LRU cache of rendered artifacts.
 *
 * <p>Keys are strings computed by the renderer and cover every rendering
 * input (renderer version, own source hash and the transitive dependency
 * hashes), so a key hit can never return stale content.
 *
 * <p>Explicit invalidation propagates over the recorded direct-dependency
 * edges: changing document <em>X</em> evicts every cached artifact that
 * embeds <em>X</em> directly or transitively (used on rename/delete). Normal
 * capacity eviction is LRU, hence long-running memory use is bounded by
 * {@code capacity}.
 */
public class BuildCache {

    private final Map<String, BuildArtifact> store;
    private final int capacity;

    public BuildCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BuildArtifact> eldest) {
                return size() > BuildCache.this.capacity;
            }
        };
    }

    public synchronized Optional<BuildArtifact> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    public synchronized void put(String key, BuildArtifact artifact) {
        store.put(key, artifact);
    }

    /**
     * Invalidate every cached artifact whose dependency closure contains the
     * given document id. Propagation repeats until a fixed point, so both
     * direct and transitive consumers are evicted.
     */
    public synchronized void invalidateAffected(String documentId) {
        Set<String> changedDocuments = new LinkedHashSet<>();
        changedDocuments.add(documentId);
        Deque<String> work = new ArrayDeque<>();
        work.add(documentId);

        while (!work.isEmpty()) {
            String changed = work.poll();
            List<String> removedKeys = new ArrayList<>();
            Set<String> newlyChanged = new LinkedHashSet<>();
            for (Map.Entry<String, BuildArtifact> entry : store.entrySet()) {
                BuildArtifact artifact = entry.getValue();
                if (artifact.documentId().equals(changed)
                        || artifact.dependencies().contains(changed)) {
                    removedKeys.add(entry.getKey());
                    newlyChanged.add(artifact.documentId());
                }
            }
            for (String key : removedKeys) {
                store.remove(key);
            }
            for (String id : newlyChanged) {
                if (changedDocuments.add(id)) {
                    work.add(id);
                }
            }
        }
    }

    public synchronized int size() {
        return store.size();
    }

    public synchronized void clear() {
        store.clear();
    }
}
