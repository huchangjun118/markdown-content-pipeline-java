package com.github.huchangjun118.markdowncontentpipelinejava.parse;

import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocumentMeta;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Markdown sources: extracts YAML front matter, metadata,
 * include directives ({@code @include path}) and internal references
 * (Markdown links to other {@code .md} documents).
 *
 * <p>Parsing is purely functional: the same source always yields the same
 * parsed representation, independent of build order or concurrency.
 */
@Component
public class MarkdownParser {

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*@include\\s+(?:\"([^\"]+)\"|'([^']+)'|(\\S+))\\s*$");

    private static final Pattern LINK_PATTERN =
            Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    public ParsedDocument parse(String relativePath, String content) {
        FrontMatter fm = extractFrontMatter(content);
        String body = fm.body;
        int bodyLineOffset = fm.lineOffset;

        List<ParsedDocument.RawRef> includes = new ArrayList<>();
        List<ParsedDocument.RawRef> references = new ArrayList<>();

        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = bodyLineOffset + i + 1;

            Matcher inc = INCLUDE_PATTERN.matcher(line);
            if (inc.matches()) {
                String target = inc.group(1) != null ? inc.group(1)
                        : inc.group(2) != null ? inc.group(2) : inc.group(3);
                includes.add(new ParsedDocument.RawRef(target.trim(), lineNo,
                        ParsedDocument.RawRef.Kind.INCLUDE));
                continue;
            }

            Matcher link = LINK_PATTERN.matcher(line);
            while (link.find()) {
                String target = link.group(2).trim();
                if (isInternalMarkdownLink(target)) {
                    references.add(new ParsedDocument.RawRef(target, lineNo,
                            ParsedDocument.RawRef.Kind.REFERENCE));
                }
            }
        }

        return new ParsedDocument(
                DocId.fromPath(relativePath),
                relativePath,
                fm.meta,
                content,
                body,
                List.copyOf(includes),
                List.copyOf(references));
    }

    private static boolean isInternalMarkdownLink(String target) {
        if (target.startsWith("#")) return false;
        if (target.contains("://") || target.startsWith("mailto:")) return false;
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        return target.endsWith(".md");
    }

    private FrontMatter extractFrontMatter(String content) {
        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            int fenceEnd = content.indexOf("\n---", 3);
            if (fenceEnd > 0) {
                int bodyStart = content.indexOf('\n', fenceEnd + 1) + 1;
                String fmText = content.substring(4, fenceEnd);
                Map<String, String> values = parseFrontMatterLines(fmText);
                String title = values.getOrDefault("title", "");
                List<String> tags = parseTags(values.get("tags"));
                Map<String, String> extra = new LinkedHashMap<>(values);
                extra.remove("title");
                extra.remove("tags");
                String body = content.substring(bodyStart);
                int lineOffset = content.substring(0, bodyStart).split("\n", -1).length - 1;
                return new FrontMatter(new DocumentMeta(title, tags, Map.copyOf(extra)), body, lineOffset);
            }
        }
        return new FrontMatter(new DocumentMeta("", List.of(), Map.of()), content, 0);
    }

    private static Map<String, String> parseFrontMatterLines(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        List<String> tags = new ArrayList<>();
        for (String t : s.split(",")) {
            String tag = t.trim();
            if (!tag.isEmpty()) tags.add(tag);
        }
        return List.copyOf(tags);
    }

    private record FrontMatter(DocumentMeta meta, String body, int lineOffset) {
    }
}
