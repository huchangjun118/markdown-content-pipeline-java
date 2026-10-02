package com.github.huchangjun118.markdowncontentpipelinejava.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, deterministic Markdown-to-HTML renderer.
 *
 * <p>Supported block syntax: ATX headings ({@code #}..{@code ######}), fenced
 * code blocks (```` ``` ````), block quotes ({@code >}), unordered lists
 * ({@code -}, {@code *}, {@code +}) and ordered lists ({@code 1.}), horizontal
 * rules, and paragraphs separated by blank lines.
 *
 * <p>Supported inline syntax: code spans, links ({@code [text](href)}),
 * bold/italic ({@code **}, {@code __}, {@code *}, {@code _}) and HTML escaping.
 *
 * <p>The renderer is intentionally dependency-free and uses no mutable global
 * state, clocks or randomness; identical input always yields identical output
 * on every JVM.
 */
public final class MarkdownRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern HR = Pattern.compile("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$");
    private static final Pattern UL_ITEM = Pattern.compile("^\\s{0,3}[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^\\s{0,3}\\d+[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*(\\w*)\\s*$");

    private MarkdownRenderer() {
    }

    public static String toHtml(String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            if (line.isBlank()) {
                i++;
                continue;
            }

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                String lang = fence.group(2);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !isClosingFence(lines[i], marker)) {
                    if (code.length() > 0) {
                        code.append('\n');
                    }
                    code.append(lines[i]);
                    i++;
                }
                if (i < lines.length) {
                    i++; // consume closing fence
                }
                out.append("<pre><code");
                if (lang != null && !lang.isEmpty()) {
                    out.append(" data-lang=\"").append(escapeHtml(lang)).append('"');
                }
                out.append('>').append(escapeHtml(code.toString())).append("</code></pre>\n");
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                out.append("<h").append(level).append('>')
                        .append(renderInline(heading.group(2)))
                        .append("</h").append(level).append(">\n");
                i++;
                continue;
            }

            if (HR.matcher(line).matches()) {
                out.append("<hr/>\n");
                i++;
                continue;
            }

            Matcher quote = QUOTE.matcher(line);
            if (quote.matches()) {
                StringBuilder quoted = new StringBuilder();
                while (i < lines.length) {
                    Matcher quoteLine = QUOTE.matcher(lines[i]);
                    if (!quoteLine.matches()) {
                        break;
                    }
                    if (quoted.length() > 0) {
                        quoted.append('\n');
                    }
                    quoted.append(quoteLine.group(1));
                    i++;
                }
                out.append("<blockquote>").append(toHtml(quoted.toString()).trim())
                        .append("</blockquote>\n");
                continue;
            }

            if (UL_ITEM.matcher(line).matches()) {
                i = consumeList(lines, i, UL_ITEM, out, "ul");
                continue;
            }
            if (OL_ITEM.matcher(line).matches()) {
                i = consumeList(lines, i, OL_ITEM, out, "ol");
                continue;
            }

            // Paragraph: consume consecutive non-blank, non-special lines.
            StringBuilder para = new StringBuilder();
            while (i < lines.length && !lines[i].isBlank()
                    && !HEADING.matcher(lines[i]).matches()
                    && !FENCE.matcher(lines[i]).matches()
                    && !UL_ITEM.matcher(lines[i]).matches()
                    && !OL_ITEM.matcher(lines[i]).matches()
                    && !QUOTE.matcher(lines[i]).matches()
                    && !HR.matcher(lines[i]).matches()) {
                if (para.length() > 0) {
                    para.append('\n');
                }
                para.append(lines[i]);
                i++;
            }
            out.append("<p>").append(renderInline(para.toString())).append("</p>\n");
        }

        return out.toString();
    }

    private static int consumeList(String[] lines, int start, Pattern itemPattern,
                                   StringBuilder out, String tag) {
        int i = start;
        out.append('<').append(tag).append(">\n");
        while (i < lines.length) {
            Matcher item = itemPattern.matcher(lines[i]);
            if (!item.matches()) {
                break;
            }
            String content = item.group(1);
            out.append("<li>").append(renderInline(content)).append("</li>\n");
            i++;
        }
        out.append("</").append(tag).append(">\n");
        return i;
    }

    private static boolean isClosingFence(String line, String openingMarker) {
        Matcher m = FENCE.matcher(line);
        if (!m.matches()) {
            return false;
        }
        String marker = m.group(1);
        return marker.charAt(0) == openingMarker.charAt(0)
                && marker.length() >= openingMarker.length()
                && m.group(2).isEmpty();
    }

    private static final Pattern INLINE_TOKEN = Pattern.compile(
            "(`+)([^`]*?)\\1"                                // code span
            + "|\\[([^]]*)]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)"  // link
            + "|(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\5"          // strong
            + "|(\\*|_)(?=\\S)(.+?)(?<=\\S)\\7");            // emphasis

    /**
     * Render inline markdown by scanning tokens in one pass. Plain text between
     * tokens is HTML-escaped; code span content is escaped but otherwise
     * literal, so it can never interfere with other markup.
     */
    static String renderInline(String text) {
        Matcher matcher = INLINE_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
          out.append(escapeHtml(text.substring(last, matcher.start())));
          if (matcher.group(1) != null) {
              out.append("<code>").append(escapeHtml(matcher.group(2))).append("</code>");
          } else if (matcher.group(3) != null) {
              out.append("<a href=\"").append(escapeHtml(matcher.group(4))).append("\">")
                      .append(renderInline(matcher.group(3))).append("</a>");
          } else if (matcher.group(5) != null) {
              out.append("<strong>").append(renderInline(matcher.group(6))).append("</strong>");
          } else if (matcher.group(7) != null) {
              out.append("<em>").append(renderInline(matcher.group(8))).append("</em>");
          }
          last = matcher.end();
        }
        out.append(escapeHtml(text.substring(last)));
        return out.toString();
    }

    /** Escape HTML special characters in text. */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
