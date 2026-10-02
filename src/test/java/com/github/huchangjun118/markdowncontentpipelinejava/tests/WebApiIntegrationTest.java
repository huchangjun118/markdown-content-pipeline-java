package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Boots the full application against a temp content directory and exercises
 * the HTTP contract: startup build, reads, versions, incremental build and
 * rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "content.pipeline.source-dir=${java.io.tmpdir}/mcp-web-test",
        "content.pipeline.build-on-startup=true"
})
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void startupBuildAndHttpFlow() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mcp-web-test");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("hello.md"), "# Hello\n\nworld\n");
        Files.writeString(dir.resolve("inc.md"), "before\n");

        // Trigger a full build after placing files.
        mvc.perform(post("/api/build").param("trigger", "it-full"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.version").isNumber());

        mvc.perform(get("/api/content/hello.md"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Id", org.hamcrest.Matchers.notNullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hello")));

        mvc.perform(get("/api/documents/hello.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("hello.md"))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("<h1>")));

        mvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentCount").value(2));

        mvc.perform(get("/api/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-1:].version").value(2));

        // Change a file and incrementally rebuild through the API.
        Files.writeString(dir.resolve("inc.md"), "after change\n");
        String body = "{\"changes\":{\"inc.md\":\"MODIFIED\"}}";
        mvc.perform(post("/api/build/incremental")
                        .param("trigger", "it-inc")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reusedArtifacts").value(1));

        // Rollback to the manual full build (version 2).
        mvc.perform(post("/api/versions/2/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        mvc.perform(get("/api/versions/2/content/hello.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("hello.md"));

        mvc.perform(get("/api/versions/999"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/content/missing.md"))
                .andExpect(status().isNotFound());
    }
}
