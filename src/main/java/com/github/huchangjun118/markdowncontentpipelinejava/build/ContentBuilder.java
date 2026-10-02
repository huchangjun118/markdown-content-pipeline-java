package com.github.huchangjun118.markdowncontentpipelinejava.build;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ContentProduct;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a single parsed document into a content product.
 *
 * <p>Rendering is deterministic: the same inputs always produce the same HTML,
 * regardless of build order or concurrency. Included documents are inlined at
 * the {@code @include} directive; internal {@code .md} links are rewritten to
 * content links; external links are kept as-is.
 */
@Component
public class ContentBuilder {

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*@include\\s+(?:\"([^\"]+)\"|'([^']+)'|(\\S+))\\s*$");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern LINK_PATTERN =
            Pattern.compile("\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern IMAGE_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");

    public ContentProduct build(ParsedDocument doc,
                                Map<DocId, ContentProduct> directIncludes,
                                Function<String, DocId> includeResolver,
                                Function<String, DocId> linkResolver,
                                String contentHash,
                                Set<DocId> dependencies) {
        String title = doc.meta().title();
        String html = renderBody(doc.body(), directIncludes, includeResolver, linkResolver);
        if (title.isBlank()) {
            title = firstHeading(doc.body());
        }
        if (title.isBlank()) {
            title = doc.id().value();
        }
        return new ContentProduct(doc.id(), doc.sourcePath(), title, html, contentHash, Set.copyOf(dependencies));
    }

    private String renderBody(String body,
                              Map<DocId, ContentProduct> directIncludes,
                              Function<String, DocId> includeResolver,
                              Function<String, DocId> linkResolver) {
        StringBuilder html = new StringBuilder();
        String[] lines = body.split("\n", -1);
        boolean inCodeBlock = false;
        StringBuilder codeBuf = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    html.append("<pre><code>").append(escape(codeBuf.toString())).append("</code></pre>\n");
                    inCodeBlock = false;
                    codeBuf = null;
                } else {
                    inCodeBlock = true;
                    codeBuf = new StringBuilder();
                }
                continue;
            }

            if (inCodeBlock) {
                codeBuf.append(line).append('\n');
                continue;
            }

            Matcher inc = INCLUDE_PATTERN.matcher(line);
            if (inc.matches()) {
                String target = inc.group(1) != null ? inc.group(1)
                        : inc.group(2) != null ? inc.group(2) : inc.group(3);
                DocId targetId = includeResolver.apply(target.trim());
                if (targetId != null) {
                    ContentProduct included = directIncludes.get(targetId);
                    if (included != null) {
                        html.append(included.html()).append('\n');
                    }
                }
                continue;
            }

            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher heading = HEADING_PATTERN.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String text = heading.group(2).trim();
                String slug = slugify(text);
                html.append("<h").append(level).append(" id=\"").append(slug).append("\">")
                        .append(renderInline(text, linkResolver))
                        .append("</h").append(level).append(">\n");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                html.append("<ul><li>").append(renderInline(trimmed.substring(2), linkResolver))
                        .append("</li></ul>\n");
                continue;
            }

            html.append("<p>").append(renderInline(line, linkResolver)).append("</p>\n");
        }

        if (inCodeBlock) {
            html.append("<pre><code>").append(escape(codeBuf.toString())).append("</code></pre>\n");
        }
        return html.toString();
    }

    private String renderInline(String text, Function<String, DocId> linkResolver) {
        // Images first
        Matcher img = IMAGE_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (img.find()) {
            img.appendReplacement(sb, Matcher.quoteReplacement(
                    "<img src=\"" + escape(img.group(2)) + "\" alt=\"" + escape(img.group(1)) + "\"/>"));
        }
        img.appendTail(sb);
        text = sb.toString();

        // Links
        Matcher link = LINK_PATTERN.matcher(text);
        sb = new StringBuilder();
        while (link.find()) {
            String label = link.group(1);
            String target = link.group(2);
            DocId targetId = linkResolver.apply(target);
            String href;
            if (targetId != null) {
                href = "content/" + targetId.value();
            } else {
                href = escape(target);
            }
            link.appendReplacement(sb, Matcher.quoteReplacement(
                    "<a href=\"" + href + "\">" + renderInline(label, linkResolver) + "</a>"));
        }
        link.appendTail(sb);
        text = sb.toString();

        // Code
        text = replacePattern(text, CODE_PATTERN, m -> "<code>" + escape(m.group(1)) + "</code>");
        // Bold then italic
        text = replacePattern(text, BOLD_PATTERN, m -> "<strong>" + renderInline(m.group(1), linkResolver) + "</strong>");
        text = replacePattern(text, ITALIC_PATTERN, m -> "<em>" + renderInline(m.group(1), linkResolver) + "</em>");
        return text;
    }

    private interface Replacer {
        String replace(Matcher m);
    }

    private static String replacePattern(String text, Pattern pattern, Replacer replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.replace(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String firstHeading(String body) {
        for (String line : body.split("\n", -1)) {
            Matcher h = HEADING_PATTERN.matcher(line);
            if (h.matches()) {
                return h.group(2).trim();
            }
        }
        return "";
    }

    private static String slugify(String text) {
        String slug = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "section" : slug;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
