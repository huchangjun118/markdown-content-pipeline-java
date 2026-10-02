package com.github.huchangjun118.markdowncontentpipelinejava.api;

import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Version API: history, version detail, rollback.
 *
 * <p>Versions are immutable. Rollback creates a new version with the target
 * version's manifest, so reads return exactly the historical content. Publish
 * and rollback are serialized, so concurrent calls have a deterministic order.
 */
@RestController
@RequestMapping("/api/versions")
public class VersionController {

    private final VersionStore versionStore;

    public VersionController(VersionStore versionStore) {
        this.versionStore = versionStore;
    }

    @GetMapping
    public List<Map<String, Object>> history() {
        return versionStore.history().stream().map(VersionController::summary).toList();
    }

    @GetMapping("/{versionId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long versionId) {
        return versionStore.version(versionId)
                .map(v -> ResponseEntity.of(java.util.Optional.of(detail(v))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{versionId}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable long versionId) {
        try {
            BuildVersion rolled = versionStore.rollbackTo(versionId);
            return ResponseEntity.ok(summary(rolled));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", e.getMessage());
            return ResponseEntity.status(409).body(body);
        }
    }

    private static Map<String, Object> summary(BuildVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", v.versionId());
        m.put("type", v.type());
        m.put("state", v.state());
        m.put("parentVersionId", v.parentVersionId());
        m.put("sourceVersionId", v.sourceVersionId());
        m.put("documents", v.manifest().size());
        m.put("createdAt", v.createdAt());
        return m;
    }

    private static Map<String, Object> detail(BuildVersion v) {
        Map<String, Object> m = summary(v);
        m.put("manifest", v.manifest());
        m.put("sourceFingerprint", v.sourceFingerprint());
        return m;
    }
}
