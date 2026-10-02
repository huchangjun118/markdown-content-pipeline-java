package com.github.huchangjun118.markdowncontentpipelinejava.web;

import com.github.huchangjun118.markdowncontentpipelinejava.coordinator.BuildCoordinator;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildResult;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ChangeType;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentVersion;
import com.github.huchangjun118.markdowncontentpipelinejava.model.VersionInfo;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP API for content reads, builds, versions and rollback.
 *
 * <pre>
 * GET  /api/content                          list current version documents
 * GET  /api/content/{id:.+}                  read one rendered document (HTML)
 * GET  /api/documents/{id:.+}              read one rendered document (JSON)
 * GET  /api/versions                         list versions in history
 * GET  /api/versions/{version}               fetch one immutable version
 * GET  /api/versions/{version}/content/{id}  read a document from an old version
 * POST /api/build?trigger=...                full rebuild
 * POST /api/build/incremental                incremental rebuild (change list)
 * POST /api/versions/{version}/rollback      atomically republish an old version
 * </pre>
 *
 * Reads hit only immutable committed versions; they never block a running
 * build and never observe a half-built state.
 */
@RestController
@RequestMapping("/api")
public class ContentController {

    private final BuildCoordinator coordinator;

    public ContentController(BuildCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> listCurrent() {
        Optional<ContentVersion> current = coordinator.currentVersion();
        if (current.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(versionSummary(current.get()));
    }

    @GetMapping(value = "/content/{id:.+}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> readHtml(@PathVariable String id) {
        Optional<BuildArtifact> artifact = coordinator.read(id);
        return artifact
                .map(a -> ResponseEntity.ok()
                        .header("X-Content-Id", a.contentId())
                        .body(wrapHtml(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/documents/{id:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> readJson(@PathVariable String id) {
        Optional<BuildArtifact> artifact = coordinator.read(id);
        return artifact.<ResponseEntity<Map<String, Object>>>map(a -> ResponseEntity.ok(toJson(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/versions")
    public List<VersionInfo> listVersions() {
        return coordinator.listVersions();
    }

    @GetMapping("/versions/{version}")
    public ResponseEntity<Map<String, Object>> getVersion(@PathVariable long version) {
        return coordinator.readVersion(version)
                .<ResponseEntity<Map<String, Object>>>map(v -> ResponseEntity.ok(versionSummary(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/versions/{version}/content/{id:.+}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> readVersionDocument(
            @PathVariable long version, @PathVariable String id) {
        Optional<ContentVersion> contentVersion = coordinator.readVersion(version);
        if (contentVersion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BuildArtifact artifact = contentVersion.get().artifact(id);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toJson(artifact));
    }

    @PostMapping("/build")
    public ResponseEntity<BuildResult> fullBuild(@RequestParam(defaultValue = "manual-full")
                                                 String trigger) {
        BuildResult result = coordinator.fullRebuild(trigger);
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/build/incremental")
    public ResponseEntity<BuildResult> incrementalBuild(
            @RequestParam(defaultValue = "manual-incremental") String trigger,
            @RequestBody(required = false) IncrementalRequest request) {
        Map<String, ChangeType> changes = new LinkedHashMap<>();
        if (request != null && request.changes() != null) {
            for (Map.Entry<String, String> entry : request.changes().entrySet()) {
                ChangeType type;
                try {
                    type = ChangeType.valueOf(entry.getValue().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    type = ChangeType.MODIFIED;
                }
                changes.put(entry.getKey(), type);
            }
        }
        BuildResult result = coordinator.incrementalRebuild(changes, trigger);
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/versions/{version}/rollback")
    public ResponseEntity<Object> rollback(@PathVariable long version,
                                           @RequestParam(defaultValue = "rollback") String trigger) {
        return coordinator.rollback(version, trigger)
                .<ResponseEntity<Object>>map(v -> ResponseEntity.ok(versionSummary(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Map<String, Object> versionSummary(ContentVersion version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version.version());
        body.put("createdAt", version.createdAt());
        body.put("trigger", version.trigger());
        body.put("documentCount", version.artifacts().size());
        body.put("errors", version.errors());
        List<Map<String, Object>> documents = new java.util.ArrayList<>();
        version.artifacts().keySet().stream().sorted().forEach(id -> {
            BuildArtifact artifact = version.artifacts().get(id);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", id);
            summary.put("title", artifact.title());
            summary.put("contentId", artifact.contentId());
            documents.add(summary);
        });
        body.put("documents", documents);
        return body;
    }

    private static Map<String, Object> toJson(BuildArtifact artifact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", artifact.documentId());
        body.put("contentId", artifact.contentId());
        body.put("title", artifact.title());
        body.put("metadata", artifact.metadata());
        body.put("dependencies", artifact.dependencies());
        body.put("html", artifact.html());
        return body;
    }

    private static String wrapHtml(BuildArtifact artifact) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<title>" + artifact.title() + "</title></head><body>"
                + artifact.html()
                + "</body></html>";
    }

    /** Request body for incremental builds: id -> ADDED/MODIFIED/DELETED. */
    public record IncrementalRequest(Map<String, String> changes) {
    }
}
