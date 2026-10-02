package com.github.huchangjun118.markdowncontentpipelinejava.api;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read API for published content products.
 *
 * <p>Reads always observe a single committed immutable version. They never take
 * the build lock, so a long-running rebuild cannot block reads and reads can
 * never observe a half-built state.
 */
@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final VersionStore versionStore;

    public ContentController(VersionStore versionStore) {
        this.versionStore = versionStore;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        BuildVersion current = versionStore.current();
        return current.manifest().keySet().stream()
                .sorted()
                .map(id -> {
                    ContentProduct p = versionStore.product(id).orElseThrow();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id.value());
                    m.put("title", p.title());
                    m.put("sourcePath", p.sourcePath());
                    return m;
                })
                .toList();
    }

    @GetMapping("/{*docId}")
    public ResponseEntity<ContentProduct> get(@PathVariable String docId) {
        String id = docId.startsWith("/") ? docId.substring(1) : docId;
        return versionStore.product(new DocId(id))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> noVersion(IllegalStateException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("hint", "Trigger a build via POST /api/build/first");
        return body;
    }
}
