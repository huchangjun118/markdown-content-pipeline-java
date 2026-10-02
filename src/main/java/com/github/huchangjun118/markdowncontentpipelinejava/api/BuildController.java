package com.github.huchangjun118.markdowncontentpipelinejava.api;

import com.github.huchangjun118.markdowncontentpipelinejava.build.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build trigger API: full rebuild and incremental rebuild.
 *
 * <p>A failed build (e.g. missing include, include cycle) does not publish a
 * version; the last published version remains readable.
 */
@RestController
@RequestMapping("/api/build")
public class BuildController {

    private final BuildEngine buildEngine;

    public BuildController(BuildEngine buildEngine) {
        this.buildEngine = buildEngine;
    }

    @PostMapping("/full")
    public ResponseEntity<Map<String, Object>> fullBuild() {
        return respond(buildEngine.fullBuild());
    }

    @PostMapping("/incremental")
    public ResponseEntity<Map<String, Object>> incrementalBuild() {
        return respond(buildEngine.incrementalBuild());
    }

    private ResponseEntity<Map<String, Object>> respond(BuildReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", report.success());
        body.put("versionId", report.versionId());
        body.put("built", report.built().stream().map(DocId::value).toList());
        body.put("reused", report.reused().stream().map(DocId::value).toList());
        body.put("affected", report.affected().stream().map(DocId::value).toList());
        body.put("errors", report.errors().stream().map(BuildError::message).toList());
        body.put("warnings", report.warnings().stream().map(BuildError::message).toList());
        if (report.success()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
