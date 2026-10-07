package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the deterministic product identifier (content hash) from every
 * input that affects the rendered product:
 *
 * <ul>
 *   <li>the builder/render version;</li>
 *   <li>the document id and source path (a product is bound to its identity);</li>
 *   <li>the document's own raw source;</li>
 *   <li>the link-resolution fingerprint: for every internal {@code .md} link,
 *       which document it resolves to (or that it is missing). Link existence
 *       changes the rendered {@code href}, so it must change the identifier;</li>
 *   <li>the product hashes of all directly included documents. Because those
 *       hashes are computed the same way, include dependencies of any depth
 *       are covered transitively.</li>
 * </ul>
 *
 * <p>The hash is a pure function of the source tree and the dependency graph:
 * it is independent of build order, build mode (full or incremental) and
 * concurrency, because every input collection is iterated in sorted order.
 * Two different products never share an identifier, and identical inputs
 * always yield the same identifier.
 */
@Component
public class ProductHasher {

    /**
     * Builder/render version: change when rendering logic or the hash formula
     * changes to invalidate every product.
     */
    public static final String BUILDER_VERSION = "content-pipeline-builder-2";

    /**
     * @param doc                 the parsed document being built
     * @param linkResolution      raw link target -> resolved doc id ({@code null}
     *                            = missing target), for every internal reference
     *                            of the document
     * @param directIncludeHashes direct include doc id -> its product hash
     */
    public String hashProduct(ParsedDocument doc,
                              Map<String, DocId> linkResolution,
                              Map<DocId, String> directIncludeHashes) {
        StringBuilder sb = new StringBuilder();
        sb.append(BUILDER_VERSION).append('\n');
        sb.append("id=").append(doc.id().value()).append('\n');
        sb.append("path=").append(doc.sourcePath()).append('\n');
        sb.append("source=").append(doc.rawContent()).append('\n');
        new TreeMap<>(linkResolution).forEach((target, resolved) ->
                sb.append("link=").append(target).append("->")
                        .append(resolved == null ? "<missing>" : resolved.value())
                        .append('\n'));
        new TreeMap<>(directIncludeHashes).forEach((id, hash) ->
                sb.append("include=").append(id.value()).append('=').append(hash).append('\n'));
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
