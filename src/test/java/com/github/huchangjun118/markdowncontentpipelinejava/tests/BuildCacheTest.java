package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.cache.BuildCache;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded LRU behavior and transitive invalidation. */
class BuildCacheTest {

    private BuildArtifact artifact(String id, List<String> deps) {
        return new BuildArtifact(id, "cid-" + id, "<html>" + id + "</html>",
                id, java.util.Map.of(), List.copyOf(deps));
    }

    @Test
    void capacityIsBoundedByLru() {
        BuildCache cache = new BuildCache(3);
        cache.put("k1", artifact("d1", List.of()));
        cache.put("k2", artifact("d2", List.of()));
        cache.put("k3", artifact("d3", List.of()));
        // touch k1 so k2 is LRU.
        assertTrue(cache.get("k1").isPresent());
        cache.put("k4", artifact("d4", List.of()));
        assertTrue(cache.get("k1").isPresent());
        assertTrue(cache.get("k2").isEmpty(), "LRU entry must be evicted");
        assertTrue(cache.get("k3").isPresent());
        assertTrue(cache.get("k4").isPresent());
        assertEquals(3, cache.size());
    }

    @Test
    void invalidatePropagatesAcrossTransitiveConsumers() {
        BuildCache cache = new BuildCache(100);
        // a <- b <- c (c includes b includes a); x is unrelated.
        cache.put("ka", artifact("a.md", List.of()));
        cache.put("kb", artifact("b.md", List.of("a.md")));
        cache.put("kc", artifact("c.md", List.of("b.md")));
        cache.put("kx", artifact("x.md", List.of()));

        cache.invalidateAffected("a.md");

        assertTrue(cache.get("ka").isEmpty());
        assertTrue(cache.get("kb").isEmpty(), "direct consumer must be evicted");
        assertTrue(cache.get("kc").isEmpty(), "transitive consumer must be evicted");
        assertTrue(cache.get("kx").isPresent(), "unrelated entry must stay");
    }

    @Test
    void clearEmptiesCache() {
        BuildCache cache = new BuildCache(10);
        cache.put("k", artifact("d", List.of()));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(Optional.empty(), cache.get("k"));
    }
}
