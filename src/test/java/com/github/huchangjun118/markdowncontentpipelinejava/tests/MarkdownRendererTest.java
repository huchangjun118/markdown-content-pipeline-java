package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.util.MarkdownRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    @Test
    void rendersCommonBlocksDeterministically() {
        String md = """
                # Title

                A paragraph with **bold** and *italic* and `code`.

                - one
                - two

                1. first
                2. second

                > quoted

                ---

                ```java
                int x = 1 < 2;
                ```
                """;
        String a = MarkdownRenderer.toHtml(md);
        String b = MarkdownRenderer.toHtml(md.replace("\n", "\r\n"));
        assertEquals(a, b, "CRLF input must normalize");

        assertTrue(a.contains("<h1>Title</h1>"));
        assertTrue(a.contains("<strong>bold</strong>"));
        assertTrue(a.contains("<em>italic</em>"));
        assertTrue(a.contains("<code>code</code>"));
        assertTrue(a.contains("<ul>"));
        assertTrue(a.contains("<li>one</li>"));
        assertTrue(a.contains("<ol>"));
        assertTrue(a.contains("<blockquote>"));
        assertTrue(a.contains("<hr/>"));
        // Code content is escaped, not interpreted.
        assertTrue(a.contains("int x = 1 &lt; 2;"));
        assertTrue(a.contains("<strong>bold</strong>"));
    }

    @Test
    void escapesUnsafeHtml() {
        String html = MarkdownRenderer.toHtml("<script>alert('x')</script>\n\n");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&#39;"));
    }

    @Test
    void rendersLinks() {
        String html = MarkdownRenderer.toHtml("[label](http://x/y)\n\n");
        assertTrue(html.contains("<a href=\"http://x/y\">label</a>"));
    }
}
