package com.github.huchangjun118.markdowncontentpipelinejava.cache;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuildCacheTest {

    private static ContentProduct product(String doc, String hash) {
        DocId id = DocId.fromPath(doc);
        return new ContentProduct(id, doc, "t", "<p>x</p>", hash, Set.of());
    }

    @Test
    void cacheIsBoundedByLruEviction() {
        BuildCache cache = new BuildCache(3);
        for (int i = 0; i < 10; i++) {
            cache.put(DocId.fromPath("d" + i + ".md"), "h" + i, product("d" + i + ".md", "h" + i));
        }
        assertThat(cache.size()).isLessThanOrEqualTo(3);
        // Oldest entries were evicted; newest are still present.
        assertThat(cache.get(DocId.fromPath("d0.md"), "h0")).isEmpty();
        assertThat(cache.get(DocId.fromPath("d9.md"), "h9")).isPresent();
    }

    @Test
    void staleEntryIsUnreachableUnderANewContentHash() {
        BuildCache cache = new BuildCache(10);
        DocId id = DocId.fromPath("a.md");
        // Product built while the link target existed.
        cache.put(id, "hash-with-target", product("a.md", "hash-with-target"));
        // After the target disappears the document gets a new identifier, so
        // the stale entry can never be served for the new state.
        assertThat(cache.get(id, "hash-without-target")).isEmpty();
        // The old entry is still only reachable under its own identifier.
        assertThat(cache.get(id, "hash-with-target")).isPresent();
    }

    @Test
    void evictDocRemovesAllEntriesOfADocument() {
        BuildCache cache = new BuildCache(10);
        DocId id = DocId.fromPath("a.md");
        cache.put(id, "h1", product("a.md", "h1"));
        cache.put(id, "h2", product("a.md", "h2"));
        cache.evictDoc(id);
        assertThat(cache.get(id, "h1")).isEmpty();
        assertThat(cache.get(id, "h2")).isEmpty();
    }
}
