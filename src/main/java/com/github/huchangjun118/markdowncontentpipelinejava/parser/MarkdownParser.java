package com.github.huchangjun118.markdowncontentpipelinejava.parser;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses markdown source deterministically.
 *
 * <p>Extracted references:
 * <ul>
 *   <li>Includes: lines containing {@code {{include path/to/doc.md}}},
 *       possibly multiple per line, in document order.</li>
 *   <li>Links: inline markdown links {@code [text](doc.md)} whose target is a
 *       relative content path (http(s)/mailto/anchor-only links are ignored).</li>
 * </ul>
 * Targets are normalized to content ids relative to the referencing document.
 * Existence is <em>not</em> checked here; the {@code DependencyResolver} does
 * that, so parsing never fails on a dangling reference.
 */
@Component
public class MarkdownParser {

    public static final Pattern INCLUDE = Pattern.compile("\\{\\{\\s*include\\s+([^}]+?)\\s*}}");
    private static final Pattern LINK = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*#*\\s*$");

    /**
     * Directive forms recognized, one per source line:
     * <pre>
     * {{include path/to/doc.md}}
     * [text](doc.md) or [text](doc.md#anchor)
     * </pre>
     */
    public ParsedDocument parse(SourceSnapshot source) {
        String normalized = source.content().replace("\r\n", "\n").replace("\r", "\n");
        String[] rawLines = normalized.split("\n", -1);

        Map<String, String> frontMatter = new LinkedHashMap<>();
        int bodyStart = 0;
        if (rawLines.length > 0 && rawLines[0].trim().equals("---")) {
            int end = -1;
            for (int i = 1; i < rawLines.length; i++) {
                if (rawLines[i].trim().equals("---") || rawLines[i].trim().equals("...")) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                for (int i = 1; i < end; i++) {
                    String line = rawLines[i];
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = stripQuotes(line.substring(colon + 1).trim());
                        frontMatter.put(key, value);
                    }
                }
                bodyStart = end + 1;
            }
        }

        String baseDir = dirOf(source.id());
        List<String> includes = new ArrayList<>();
        List<String> links = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        Set<String> seenIncludes = new LinkedHashSet<>();
        Set<String> seenLinks = new LinkedHashSet<>();
        StringBuilder body = new StringBuilder();
        String title = frontMatter.get("title");

        for (int idx = bodyStart; idx < rawLines.length; idx++) {
            String line = rawLines[idx];
            int lineNo = idx + 1;
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line);

            if (title == null) {
                Matcher h1 = H1.matcher(line);
                if (h1.matches()) {
                    title = h1.group(1).trim();
                }
            }

            Matcher inc = INCLUDE.matcher(line);
            while (inc.find()) {
                String target = cleanTarget(inc.group(1));
                String id = normalizeTarget(baseDir, target);
                if (seenIncludes.add(id)) {
                    includes.add(id);
                }
                positions.putIfAbsent(id, lineNo);
            }

            Matcher link = LINK.matcher(line);
            while (link.find()) {
                String destination = link.group(1).trim();
                String target = stripAnchor(destination);
                if (target.isEmpty() || !isContentPath(destination)) {
                    continue;
                }
                String id = normalizeTarget(baseDir, target);
                if (seenLinks.add(id)) {
                    links.add(id);
                }
                positions.putIfAbsent(id, lineNo);
            }
        }

        if (title == null) {
            title = humanize(fileNameStem(source.id()));
        }

        return new ParsedDocument(source.id(), source.contentHash(), frontMatter, title,
                body.toString(), List.copyOf(includes), List.copyOf(links),
                Map.copyOf(positions));
    }

    private static String cleanTarget(String raw) {
        String t = stripQuotes(raw.trim());
        return stripAnchor(t);
    }

    private static String stripAnchor(String target) {
        int hash = target.indexOf('#');
        if (hash >= 0) {
            target = target.substring(0, hash);
        }
        int query = target.indexOf('?');
        if (query >= 0) {
            target = target.substring(0, query);
        }
        return target.trim();
    }

    private static boolean isContentPath(String destination) {
        if (destination.startsWith("#")) {
            return false;
        }
        String lower = destination.toLowerCase();
        return !lower.startsWith("http://")
                && !lower.startsWith("https://")
                && !lower.startsWith("mailto:")
                && !lower.startsWith("ftp://")
                && !lower.startsWith("data:");
    }

    /**
     * Normalize a referenced path against the directory of the referencing id.
     * A leading {@code /} is content-root relative. {@code .} and {@code ..}
     * are resolved lexically; results never escape the syntactic root below
     * the first segment (a {@code ..} past the root is clamped).
     */
    public static String normalizeTarget(String baseDir, String target) {
        String path = target.replace('\\', '/');
        List<String> parts;
        if (path.startsWith("/")) {
            parts = splitSegments(path.substring(1));
        } else {
            List<String> base = splitSegments(baseDir);
            base.addAll(splitSegments(path));
            parts = base;
        }

        List<String> resolved = new ArrayList<>();
        for (String part : parts) {
            switch (part) {
                case "", "." -> { /* skip */ }
                case ".." -> {
                    if (!resolved.isEmpty()) {
                        resolved.remove(resolved.size() - 1);
                    }
                }
                default -> resolved.add(part);
            }
        }
        return String.join("/", resolved);
    }

    private static List<String> splitSegments(String path) {
        List<String> out = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (!segment.isEmpty()) {
                out.add(segment);
            }
        }
        return out;
    }

    private static String dirOf(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? "" : id.substring(0, slash);
    }

    private static String fileNameStem(String id) {
        int slash = id.lastIndexOf('/');
        String name = slash < 0 ? id : id.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String humanize(String stem) {
        String spaced = stem.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.isEmpty()) {
            return stem;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
