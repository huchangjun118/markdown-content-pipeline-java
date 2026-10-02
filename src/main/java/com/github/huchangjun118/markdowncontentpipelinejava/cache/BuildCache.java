package com.github.huchangjun118.markdowncontentpipelinejava.cache;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded content-addressed build cache.
 *
 * <p>Key = (docId, contentHash) where contentHash covers every input of the
 * product (own source + all transitively included sources). A hit guarantees
 * the product is identical to what a fresh build would produce, so cache
 * entries can be reused safely across full and incremental builds.
 *
 * <p>Long-running usage is bounded by an LRU eviction policy.
 */
@Component
public class BuildCache {

    private final int maxEntries;
    private final LinkedHashMap<String, ContentProduct> entries;

    public BuildCache(@org.springframework.beans.factory.annotation.Value("${pipeline.cache-max-entries:1000}") int maxEntries) {
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ContentProduct> eldest) {
                return size() > BuildCache.this.maxEntries;
            }
        };
    }

    private static String key(DocId docId, String contentHash) {
        return docId.value() + "@" + contentHash;
    }

    public synchronized Optional<ContentProduct> get(DocId docId, String contentHash) {
        return Optional.ofNullable(entries.get(key(docId, contentHash)));
    }

    public synchronized void put(DocId docId, String contentHash, ContentProduct product) {
        entries.put(key(docId, contentHash), product);
    }

    /** Evict every cached entry for the given document (e.g. after deletion). */
    public synchronized void evictDoc(DocId docId) {
        entries.keySet().removeIf(k -> k.startsWith(docId.value() + "@"));
    }

    public synchronized int size() {
        return entries.size();
    }
}
