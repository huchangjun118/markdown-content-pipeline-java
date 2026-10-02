package com.github.huchangjun118.markdowncontentpipelinejava.api;

import com.github.huchangjun118.markdowncontentpipelinejava.build.BuildEngine;
import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildReport;
import com.github.huchangjun118.markdowncontentpipelinejava.version.VersionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentApiIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("pipeline.source-dir", () -> tempDir.resolve("sources").toString());
        registry.add("pipeline.output-dir", () -> tempDir.resolve("output").toString());
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    BuildEngine buildEngine;

    @Autowired
    VersionStore versionStore;

    @Test
    void buildReadAndRollbackOverHttp() throws Exception {
        Path sources = tempDir.resolve("sources");
        Files.createDirectories(sources.resolve("guide"));
        Files.writeString(sources.resolve("index.md"), "# Home\nWelcome.\n");
        Files.writeString(sources.resolve("guide/intro.md"), "# Intro\n@include ../shared/notice.md\n");
        Files.createDirectories(sources.resolve("shared"));
        Files.writeString(sources.resolve("shared/notice.md"), "Notice: v1.\n");

        // Full build
        ResponseEntity<Map> buildResp = rest.postForEntity("/api/build/full", null, Map.class);
        assertThat(buildResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(buildResp.getBody().get("success")).isEqualTo(true);

        // List content
        ResponseEntity<List<Map<String, Object>>> listResp = rest.exchange(
                "/api/content", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(3);

        // Read a single document (id contains a slash)
        ResponseEntity<Map> docResp = rest.getForEntity("/api/content/guide/intro", Map.class);
        assertThat(docResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docResp.getBody().get("html").toString()).contains("Notice: v1.");

        // Versions
        ResponseEntity<List<Map<String, Object>>> versionsResp = rest.exchange(
                "/api/versions", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(versionsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versionsResp.getBody()).hasSize(1);

        // Change the included notice and rebuild
        Files.writeString(sources.resolve("shared/notice.md"), "Notice: v2.\n");
        ResponseEntity<Map> incResp = rest.postForEntity("/api/build/incremental", null, Map.class);
        assertThat(incResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(incResp.getBody().get("success")).isEqualTo(true);
        assertThat(incResp.getBody().get("affected")).asList().contains("shared/notice", "guide/intro");

        docResp = rest.getForEntity("/api/content/guide/intro", Map.class);
        assertThat(docResp.getBody().get("html").toString()).contains("Notice: v2.");

        // Roll back to version 1
        ResponseEntity<Map> rollbackResp = rest.postForEntity("/api/versions/1/rollback", null, Map.class);
        assertThat(rollbackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rollbackResp.getBody().get("type")).isEqualTo("ROLLBACK");

        // Content now matches the historical version 1
        docResp = rest.getForEntity("/api/content/guide/intro", Map.class);
        assertThat(docResp.getBody().get("html").toString()).contains("Notice: v1.");
        assertThat(docResp.getBody().get("html").toString()).doesNotContain("Notice: v2.");

        // Missing document -> 404
        ResponseEntity<Map> missingResp = rest.getForEntity("/api/content/does/not/exist", Map.class);
        assertThat(missingResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void failedBuildDoesNotPublish() throws Exception {
        Path sources = tempDir.resolve("sources");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("bad.md"), "@include missing.md\n");

        long versionBefore = versionStore.current().versionId();

        ResponseEntity<Map> buildResp = rest.postForEntity("/api/build/full", null, Map.class);
        assertThat(buildResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(buildResp.getBody().get("success")).isEqualTo(false);
        assertThat(buildResp.getBody().get("errors")).asList().isNotEmpty();

        // Failure isolation: the previously published version is untouched and readable
        assertThat(versionStore.current().versionId()).isEqualTo(versionBefore);
        ResponseEntity<List<Map<String, Object>>> listResp = rest.exchange(
                "/api/content", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
