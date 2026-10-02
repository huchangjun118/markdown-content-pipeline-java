package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/**
 * Computes the deterministic content hash of a product from all of its inputs.
 *
 * <p>A product depends on its own source and on every transitively included
 * document. References (links) do not affect the rendered output, so linked
 * documents' content is not part of the hash. The hash is independent of build
 * order and concurrency: inputs are always iterated in sorted doc-id order.
 */
@Component
public class ProductHasher {

    /**
     * Builder/render version: change when rendering logic changes to
     * invalidate every product.
     */
    public static final String BUILDER_VERSION = "content-pipeline-builder-1";

    public String hashProduct(ParsedDocument doc, Map<DocId, ParsedDocument> transitiveIncludes) {
        StringBuilder sb = new StringBuilder();
        sb.append(BUILDER_VERSION).append('\n');
        sb.append(doc.rawContent());
        sb.append('\n');
        transitiveIncludes.keySet().stream()
                .sorted()
                .forEach(id -> {
                    ParsedDocument included = transitiveIncludes.get(id);
                    sb.append(id.value()).append(':').append(included.rawContent()).append('\n');
                });
        return sha256(sb.toString());
    }

    public String hashSourceTree(Map<String, String> fileHashes) {
        StringBuilder sb = new StringBuilder();
        fileHashes.keySet().stream().sorted()
                .forEach(path -> sb.append(path).append(':').append(fileHashes.get(path)).append('\n'));
        return sha256(sb.toString());
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
