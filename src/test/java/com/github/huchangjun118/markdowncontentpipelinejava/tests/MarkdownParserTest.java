package com.github.huchangjun118.markdowncontentpipelinejava.tests;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.model.SourceSnapshot;
import com.github.huchangjun118.markdowncontentpipelinejava.parser.MarkdownParser;
import com.github.huchangjun118.markdowncontentpipelinejava.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    private ParsedDocument parse(String id, String content) {
        SourceSnapshot snapshot = SourceSnapshot.of(
                id, content, Hashing.sha256(content), 0L, content.length());
        return parser.parse(snapshot);
    }

    @Test
    void extractsFrontMatterTitleAndBody() {
        ParsedDocument doc = parse("p.md", """
                ---
                title: Custom Title
                tags: a, b
                ---
                # Heading One

                body text
                """);
        assertEquals("Custom Title", doc.title(), "front matter title wins");
        assertEquals("a, b", doc.frontMatter().get("tags"));
        assertTrue(doc.bodyMarkdown().contains("body text"));
        assertFalse(doc.bodyMarkdown().contains("---"));
    }

    @Test
    void fallsBackToH1ThenFileName() {
        assertEquals("Heading", parse("x.md", "# Heading\n").title());
        assertEquals("My doc", parse("my-doc.md", "just body\n").title());
    }

    @Test
    void parsesIncludesWithPositionsAndRelativeResolution() {
        ParsedDocument doc = parse("docs/sub/page.md",
                "# Page\n\ntext\n\n{{include ../shared/note.md}}\n");
        assertEquals(1, doc.includes().size());
        assertEquals("docs/shared/note.md", doc.includes().get(0));
        assertEquals(5, doc.positions().get("docs/shared/note.md"));
    }

    @Test
    void parsesLinksButIgnoresExternalAndAnchorOnly() {
        ParsedDocument doc = parse("docs/page.md",
                "[ext](https://example.com/x) [local](other.md) [anchor](#sec) [mail](mailto:a@b.c)\n");
        assertEquals(1, doc.links().size());
        assertEquals("docs/other.md", doc.links().get(0));
    }

    @Test
    void rootRelativePathsNormalizeFromContentRoot() {
        assertEquals("top/x.md",
                MarkdownParser.normalizeTarget("a/b", "/top/x.md"));
        assertEquals("a/x.md",
                MarkdownParser.normalizeTarget("a/b", "../x.md"));
        assertEquals("a/x.md",
                MarkdownParser.normalizeTarget("a/b/c", "../../x.md"));
    }

    @Test
    void duplicateReferencesKeepFirstPosition() {
        ParsedDocument doc = parse("a.md",
                "{{include b.md}}\n\n{{include b.md}}\n");
        assertEquals(1, doc.includes().size());
        assertEquals(1, doc.positions().get("b.md"));
    }
}
