package com.github.huchangjun118.markdowncontentpipelinejava.render;

import com.github.huchangjun118.markdowncontentpipelinejava.config.ContentPipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildArtifact;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.CyclePolicy;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ErrorKind;
import com.github.huchangjun118.markdowncontentpipelinejava.model.MissingRefPolicy;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.util.Hashing;
import com.github.huchangjun118.markdowncontentpipelinejava.util.MarkdownRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default deterministic artifact renderer.
 *
 * <p>Rendering of one document is a pure function of:
 * <ol>
 *   <li>the renderer version (bumped on any rendering change),</li>
 *   <li>the active cycle / missing-reference policies and depth limit,</li>
 *   <li>the document's own source hash,</li>
 *   <li>the cache keys of every document it includes, recursively, and</li>
 *   <li>the existence of every document it links to.</li>
 * </ol>
 * All of these are concatenated (in document order) into the cache key which
 * is also the artifact's {@code contentId}. Identical inputs therefore yield
 * identical bytes and identical ids, regardless of build order or which
 * documents were rebuilt.
 */
@Component
public class DefaultArtifactRenderer implements ArtifactRenderer {

    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\(([^)]+)\\)");
    private static final Pattern FENCE = Pattern.compile("^(\\s*)(`{3,}|~{3,}).*$");

    private final ContentPipelineProperties properties;

    public DefaultArtifactRenderer(ContentPipelineProperties properties) {
        this.properties = properties;
    }

    @Override
    public String cacheKey(
            String documentId,
            Map<String, ParsedDocument> parsed,
            DependencyGraph graph,
            Map<String, String> keyMemo) {
        ParsedDocument doc = parsed.get(documentId);
        if (doc == null) {
            return null;
        }
        String cached = keyMemo.get(documentId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String key = computeKey(documentId, doc, parsed, graph, keyMemo, new LinkedHashSet<>());
        keyMemo.put(documentId, key == null ? "" : key);
        return key;
    }

    private String computeKey(
            String documentId,
            ParsedDocument doc,
            Map<String, ParsedDocument> parsed,
            DependencyGraph graph,
            Map<String, String> memo,
            Set<String> stack) {

        // A cycle edge: the key component depends only on the cycle SCC id,
        // which is identical from whichever document of the SCC it is traversed,
        // so keys never depend on traversal order.
        if (stack.contains(documentId)) {
            String sccId = graph.cyclicScc().get(documentId);
            if (sccId == null || properties.getCyclePolicy() == CyclePolicy.FAIL) {
                return null;
            }
            return "cyc:" + sccId;
        }

        List<String> parts = new ArrayList<>();
        parts.add("renderer:" + properties.getRendererVersion());
        parts.add("cycle:" + properties.getCyclePolicy());
        parts.add("missing:" + properties.getMissingRefPolicy());
        parts.add("depth:" + properties.getMaxIncludeDepth());
        parts.add("src:" + doc.contentHash());

        stack.add(documentId);
        for (String target : doc.includes()) {
            if (!parsed.containsKey(target)) {
                if (properties.getMissingRefPolicy() == MissingRefPolicy.FAIL) {
                    stack.remove(documentId);
                    return null;
                }
                parts.add("missing-include:" + target);
                continue;
            }
            String childKey;
            String existing = memo.get(target);
            if (existing != null) {
                childKey = existing.isEmpty() ? null : existing;
            } else {
                childKey = computeKey(target, parsed.get(target), parsed, graph, memo, stack);
                memo.put(target, childKey == null ? "" : childKey);
            }
            if (childKey == null) {
                stack.remove(documentId);
                return null;
            }
            parts.add("include:" + target + "=" + childKey);
        }
        stack.remove(documentId);

        // Links affect rendering only through target existence; target content
        // is not embedded.
        for (String target : doc.links()) {
            parts.add("link:" + target + "=" + (parsed.containsKey(target) ? "present" : "absent"));
        }
        return Hashing.sha256(String.join("|", parts));
    }

    @Override
    public Optional<BuildArtifact> render(
            String documentId,
            Map<String, ParsedDocument> parsed,
            Map<String, BuildArtifact> rendered,
            DependencyGraph graph,
            List<BuildError> errors) {
        ParsedDocument doc = parsed.get(documentId);
        if (doc == null) {
            errors.add(BuildError.fatal(documentId, ErrorKind.IO_ERROR, 0,
                    "document not found in source set: " + documentId));
            return Optional.empty();
        }
        try {
            // Fragment memo for this document render: keyed by id + remaining
            // depth. Other top-level artifacts are not reused here, so
            // depth-dependent content cannot leak across documents.
            Map<String, String> fragmentMemo = new HashMap<>();
            Set<String> failed = new LinkedHashSet<>();
            String expandedMarkdown = expand(doc, parsed, graph,
                    properties.getMaxIncludeDepth(), errors, fragmentMemo, failed);
            if (failed.contains(documentId)) {
                return Optional.empty();
            }
            // Includes embed markdown; the fully expanded document is converted
            // to HTML once, at the root, so block nesting stays deterministic.
            String html = MarkdownRenderer.toHtml(expandedMarkdown);

            emitLinkDiagnostics(doc, parsed, errors);

            List<String> dependencies = directResolvedIncludes(doc, parsed);

            Map<String, String> keyMemo = new HashMap<>();
            String contentId = cacheKey(documentId, parsed, graph, keyMemo);
            if (contentId == null) {
                // Non-FAIL policies can produce HTML even on broken references;
                // fall back to a deterministic own-content key.
                contentId = Hashing.sha256("artifact|" + properties.getRendererVersion()
                        + "|" + documentId + "|" + doc.contentHash());
            }

            return Optional.of(new BuildArtifact(
                    documentId, contentId, html, doc.title(),
                    Map.copyOf(doc.frontMatter()), dependencies));
        } catch (RuntimeException e) {
            errors.add(BuildError.fatal(documentId, ErrorKind.RENDER_ERROR, 0,
                    "rendering failed: " + safeMessage(e)));
            return Optional.empty();
        }
    }

    /**
     * Expand include directives in one markdown body. Fenced code blocks are
     * passed through verbatim so their braces and links are never interpreted.
     */
    private String expand(
            ParsedDocument owner,
            Map<String, ParsedDocument> parsed,
            DependencyGraph graph,
            int depthRemaining,
            List<BuildError> errors,
            Map<String, String> fragmentMemo,
            Set<String> failed) {

        String normalized = owner.bodyMarkdown().replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        String fenceMarker = null;

        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            int bodyLine = idx + 1;
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(2);
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker.substring(0, 3);
                } else if (marker.startsWith(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                out.append(line).append('\n');
                continue;
            }

            if (inFence || line.isBlank()) {
                out.append(line).append('\n');
                continue;
            }

            Matcher matcher = MarkdownParser.INCLUDE.matcher(line);
            if (!matcher.find()) {
                out.append(rewriteLinks(line)).append('\n');
                continue;
            }

            int last = 0;
            matcher.reset();
            while (matcher.find()) {
                out.append(line, last, matcher.start());
                String rawTarget = matcher.group(1).trim();
                String target = resolveIncludeTarget(owner.id(), rawTarget);
                int lineNo = owner.positions().getOrDefault(target, bodyLine);
                String fragment = expandOne(owner.id(), target, lineNo, parsed, graph,
                        depthRemaining, errors, fragmentMemo, failed);
                out.append(fragment);
                last = matcher.end();
            }
            if (last < line.length()) {
                out.append(rewriteLinks(line.substring(last)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String expandOne(
            String referrerId,
            String target,
            int lineNo,
            Map<String, ParsedDocument> parsed,
            DependencyGraph graph,
            int depthRemaining,
            List<BuildError> errors,
            Map<String, String> fragmentMemo,
            Set<String> failed) {

        if (failed.contains(referrerId)) {
            return "";
        }

        if (!parsed.containsKey(target)) {
            return handleMissing(referrerId, target, lineNo, errors, failed);
        }

        boolean sameCycle = graph.cyclicScc().containsKey(target)
                && graph.cyclicScc().containsKey(referrerId)
                && graph.cyclicScc().get(referrerId).equals(graph.cyclicScc().get(target));
        if (sameCycle) {
            String cycleResult = handleCycle(referrerId, target, lineNo, graph, errors, failed);
            if (cycleResult != null) {
                return cycleResult;
            }
        }

        if (depthRemaining <= 0) {
            errors.add(BuildError.warning(referrerId, ErrorKind.CYCLIC_REFERENCE, lineNo,
                    "include depth limit (" + properties.getMaxIncludeDepth()
                            + ") reached at '" + target + "' line " + lineNo));
            return "\n<!-- include depth limit: " + target + " -->\n"
                    + "<div class=\"include-depth-limit\">[include depth limit: "
                    + MarkdownRenderer.escapeHtml(target) + "]</div>\n";
        }

        String memoKey = target + "#" + depthRemaining;
        String cachedFragment = fragmentMemo.get(memoKey);
        if (cachedFragment != null) {
            return cachedFragment;
        }
        String fragment = expand(parsed.get(target), parsed, graph, depthRemaining - 1,
                errors, fragmentMemo, failed);
        if (failed.contains(target)) {
            failed.add(referrerId);
            return "";
        }
        fragmentMemo.put(memoKey, fragment);
        return fragment;
    }

    /** @return placeholder/ignored HTML, or {@code null} when the build must fail */
    private String handleMissing(
            String referrerId, String target, int lineNo,
            List<BuildError> errors, Set<String> failed) {
        switch (properties.getMissingRefPolicy()) {
            case FAIL -> {
                errors.add(BuildError.fatal(referrerId, ErrorKind.MISSING_REFERENCE, lineNo,
                        "missing include target '" + target + "' referenced at line " + lineNo));
                failed.add(referrerId);
                return "";
            }
            case PLACEHOLDER -> {
                errors.add(BuildError.warning(referrerId, ErrorKind.MISSING_REFERENCE, lineNo,
                        "missing include target '" + target + "' replaced with placeholder"
                                + " at line " + lineNo));
                return "\n<!-- missing include: " + target + " -->\n"
                        + "<div class=\"include-missing\">[missing include: "
                        + MarkdownRenderer.escapeHtml(target) + "]</div>\n";
            }
            case IGNORE -> {
                errors.add(BuildError.warning(referrerId, ErrorKind.MISSING_REFERENCE, lineNo,
                        "missing include target '" + target + "' ignored at line " + lineNo));
                return "<!-- ignored missing include: " + target + " -->\n";
            }
            default -> {
                return "";
            }
        }
    }

    /** @return marker HTML for BREAK/BOUNDED, or {@code null} under FAIL */
    private String handleCycle(
            String referrerId, String target, int lineNo,
            DependencyGraph graph, List<BuildError> errors, Set<String> failed) {
        switch (properties.getCyclePolicy()) {
            case FAIL -> {
                errors.add(BuildError.fatal(referrerId, ErrorKind.CYCLIC_REFERENCE, lineNo,
                        "cyclic include detected: '" + referrerId + "' includes '" + target
                                + "' within cycle " + graph.cyclicScc().get(target)
                                + " at line " + lineNo));
                failed.add(referrerId);
                return "";
            }
            case BREAK -> {
                errors.add(BuildError.warning(referrerId, ErrorKind.CYCLIC_REFERENCE, lineNo,
                        "cyclic include to '" + target + "' broken at line " + lineNo));
                return "\n<!-- cyclic include broken: " + target + " -->\n"
                        + "<div class=\"include-cycle\">[cyclic include: "
                        + MarkdownRenderer.escapeHtml(target) + "]</div>\n";
            }
            case BOUNDED -> {
                errors.add(BuildError.warning(referrerId, ErrorKind.CYCLIC_REFERENCE, lineNo,
                        "cyclic include to '" + target + "' bounded at line " + lineNo));
                return "\n<!-- cyclic include bounded: " + target + " -->\n"
                        + "<div class=\"include-cycle\">[cyclic include: "
                        + MarkdownRenderer.escapeHtml(target) + "]</div>\n";
            }
            default -> {
                return null;
            }
        }
    }

    private void emitLinkDiagnostics(
            ParsedDocument doc,
            Map<String, ParsedDocument> parsed,
            List<BuildError> errors) {
        for (String target : doc.links()) {
            if (parsed.containsKey(target)) {
                continue;
            }
            int line = doc.positions().getOrDefault(target, 0);
            switch (properties.getMissingRefPolicy()) {
                case FAIL -> errors.add(BuildError.fatal(doc.id(), ErrorKind.MISSING_REFERENCE,
                        line, "missing link target '" + target + "' at line " + line));
                case PLACEHOLDER, IGNORE -> errors.add(BuildError.warning(doc.id(),
                        ErrorKind.MISSING_REFERENCE, line,
                        "missing link target '" + target + "' at line " + line
                                + " (kept as written)"));
            }
        }
    }

    /** Rewrite content links to stable public URLs; external links pass through. */
    private String rewriteLinks(String line) {
        Matcher matcher = LINK.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1);
            String destination = matcher.group(2).trim();
            String rewritten = destination;
            String withoutAnchor = destination;
            String anchor = "";
            int hash = destination.indexOf('#');
            if (hash >= 0) {
                withoutAnchor = destination.substring(0, hash);
                anchor = destination.substring(hash);
            }
            if (!withoutAnchor.isEmpty()
                    && !withoutAnchor.toLowerCase().startsWith("http://")
                    && !withoutAnchor.toLowerCase().startsWith("https://")
                    && !withoutAnchor.toLowerCase().startsWith("mailto:")) {
                rewritten = "/content/" + withoutAnchor + anchor;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    "[" + label + "](" + rewritten + ")"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private List<String> directResolvedIncludes(
            ParsedDocument doc,
            Map<String, ParsedDocument> parsed) {
        Set<String> deps = new LinkedHashSet<>();
        for (String target : doc.includes()) {
            if (parsed.containsKey(target)) {
                deps.add(target);
            }
        }
        return List.copyOf(deps);
    }

    private String resolveIncludeTarget(String referrerId, String rawTarget) {
        String baseDir = "";
        int slash = referrerId.lastIndexOf('/');
        if (slash >= 0) {
            baseDir = referrerId.substring(0, slash);
        }
        String target = rawTarget;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            target = target.substring(0, hash);
        }
        return MarkdownParser.normalizeTarget(baseDir, target.trim());
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}
