package com.github.huchangjun118.markdowncontentpipelinejava.parse;

import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parsesFrontMatterAndDirectives() {
        String src = """
                ---
                title: Hello World
                tags: [intro, guide]
                author: bob
                ---
                # Intro

                See [the guide](guide/getting-started.md) for details.

                @include shared/notice.md

                External [link](https://example.com) ignored.
                """;
        ParsedDocument doc = parser.parse("docs/intro.md", src);

        assertThat(doc.id().value()).isEqualTo("docs/intro");
        assertThat(doc.meta().title()).isEqualTo("Hello World");
        assertThat(doc.meta().tags()).containsExactly("intro", "guide");
        assertThat(doc.meta().extra()).containsEntry("author", "bob");
        assertThat(doc.includes()).hasSize(1);
        assertThat(doc.includes().get(0).target()).isEqualTo("shared/notice.md");
        assertThat(doc.includes().get(0).line()).isEqualTo(10);
        assertThat(doc.references()).hasSize(1);
        assertThat(doc.references().get(0).target()).isEqualTo("guide/getting-started.md");
        assertThat(doc.body()).startsWith("# Intro");
    }

    @Test
    void noFrontMatterMeansEmptyMeta() {
        ParsedDocument doc = parser.parse("a/b.md", "# Title\n");
        assertThat(doc.meta().title()).isEmpty();
        assertThat(doc.meta().tags()).isEmpty();
        assertThat(doc.body()).isEqualTo("# Title\n");
    }
}
