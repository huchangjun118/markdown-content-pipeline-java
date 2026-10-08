package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes the deterministic content hash (product identifier) from all of the
 * inputs that affect a product's rendered output.
 *
 * <p>The rendered HTML of a document depends on:
 * <ol>
 *   <li>its own raw source;</li>
 *   <li>the raw source of every transitively included document (their rendered
 *       HTML is inlined, which in turn depends on their own references);</li>
 *   <li>the <b>resolution</b> of every {@code @include} and {@code .md} link in
 *       each of those documents: whether the target exists and, if so, which
 *       document id it resolves to. A link to an existing document is rewritten
 *       to {@code content/&lt;id&gt;}; once the target is deleted (and the
 *       missing-reference policy is WARN) the link renders verbatim, and vice
 *       versa when the target is added back.</li>
 * </ol>
 *
 * <p>Because every one of those inputs is part of the hash, two products with
 * different rendered content can never share an identifier: changing the
 * existence or identity of a referenced document changes every referencing
 * product's hash, even when the referencing source bytes themselves are
 * unchanged. The hash is independent of build order and concurrency: every
 * collection is iterated in sorted order.
 */
@Component
public class ProductHasher {

    /**
     * Builder/render version: change when rendering logic changes to
     * invalidate every product.
     */
    public static final String BUILDER_VERSION = "content-pipeline-builder-2";

    /**
     * Resolution tables for a whole build, indexed by document id.
     *
     * @param includes   doc id -> sorted map of raw include target text -> resolved doc id (or an unresolved sentinel)
     * @param references doc id -> sorted map of raw reference target text -> resolved doc id (or an unresolved sentinel)
     */
    public record ResolutionTables(
            Map<DocId, Map<String, String>> includes,
            Map<DocId, Map<String, String>> references) {

        public static ResolutionTables empty() {
            return new ResolutionTables(Map.of(), Map.of());
        }
    }

    /**
     * @param doc                 the document being built
     * @param transitiveIncludes  every doc transitively included by {@code doc}
     * @param closureOrder        sorted render closure: {@code doc} first, then all transitive includes
     * @param resolutions         include/reference resolution for every doc in the closure
     */
    public String hashProduct(ParsedDocument doc,
                              Map<DocId, ParsedDocument> transitiveIncludes,
                              List<DocId> closureOrder,
                              ResolutionTables resolutions) {
        StringBuilder sb = new StringBuilder();
        sb.append(BUILDER_VERSION).append('\n');
        for (DocId id : closureOrder) {
            ParsedDocument node = id.equals(doc.id()) ? doc : transitiveIncludes.get(id);
            sb.append("DOC ").append(id.value()).append('\n');
            sb.append(node.rawContent()).append('\n');
            appendResolutions(sb, "INC", resolutions.includes().get(id));
            appendResolutions(sb, "REF", resolutions.references().get(id));
        }
        return sha256(sb.toString());
    }

    private static void appendResolutions(StringBuilder sb, String kind, Map<String, String> resolved) {
        Map<String, String> sorted = resolved == null ? Map.of() : new TreeMap<>(resolved);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(kind).append(' ').append(e.getKey())
                    .append(" -> ").append(e.getValue()).append('\n');
        }
    }

    /**
     * Builds the sorted render closure of a document: the document itself plus
     * all of its transitive includes, in deterministic doc-id order.
     */
    public static List<DocId> closure(ParsedDocument doc, Map<DocId, ParsedDocument> transitiveIncludes) {
        List<DocId> order = new ArrayList<>(transitiveIncludes.size() + 1);
        order.add(doc.id());
        order.addAll(transitiveIncludes.keySet());
        order.sort(DocId::compareTo);
        return List.copyOf(order);
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
