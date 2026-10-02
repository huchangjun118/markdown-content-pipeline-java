package com.github.huchangjun118.markdowncontentpipelinejava.dep;

import com.github.huchangjun118.markdowncontentpipelinejava.config.PipelineProperties;
import com.github.huchangjun118.markdowncontentpipelinejava.model.BuildError;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DependencyGraph;
import com.github.huchangjun118.markdowncontentpipelinejava.model.DocId;
import com.github.huchangjun118.markdowncontentpipelinejava.model.ParsedDocument;
import com.github.huchangjun118.markdowncontentpipelinejava.parse.MarkdownParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyGraphBuilderTest {

    private final MarkdownParser parser = new MarkdownParser();

    private DependencyGraphBuilder builder(PipelineProperties.MissingRefPolicy policy) {
        PipelineProperties props = new PipelineProperties();
        props.setMissingRefPolicy(policy);
        return new DependencyGraphBuilder(props);
    }

    private List<ParsedDocument> parse(String... pathsAndContents) {
        List<ParsedDocument> docs = new java.util.ArrayList<>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            docs.add(parser.parse(pathsAndContents[i], pathsAndContents[i + 1]));
        }
        return docs;
    }

    @Test
    void resolvesIncludesAndReferences() {
        var docs = parse(
                "a.md", "@include b.md\n[a](b.md)",
                "b.md", "hello");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isFalse();
        DependencyGraph graph = result.graph();
        DocId a = DocId.fromPath("a.md");
        DocId b = DocId.fromPath("b.md");
        assertThat(graph.includeEdges().get(a)).containsExactly(b);
        assertThat(graph.referenceEdges().get(a)).containsExactly(b);
        assertThat(graph.affectedDownstream(b)).containsExactly(a);
        assertThat(graph.transitiveIncludes(a)).containsExactly(b);
    }

    @Test
    void missingIncludeIsHardErrorWithLocation() {
        var docs = parse("a.md", "@include missing.md\n");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isTrue();
        BuildError err = result.errors().get(0);
        assertThat(err.type()).isEqualTo(BuildError.ErrorType.MISSING_INCLUDE);
        assertThat(err.sourcePath()).isEqualTo("a.md");
        assertThat(err.line()).isEqualTo(1);
    }

    @Test
    void missingReferenceIsErrorByDefault() {
        var docs = parse("a.md", "[x](gone.md)\n");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors().get(0).type()).isEqualTo(BuildError.ErrorType.MISSING_REFERENCE);
    }

    @Test
    void missingReferenceCanBeWarning() {
        var docs = parse("a.md", "[x](gone.md)\n");
        var result = builder(PipelineProperties.MissingRefPolicy.WARN).build(docs);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0).type()).isEqualTo(BuildError.ErrorType.MISSING_REFERENCE);
    }

    @Test
    void includeCycleIsDetected() {
        var docs = parse(
                "a.md", "@include b.md",
                "b.md", "@include c.md",
                "c.md", "@include a.md");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).anyMatch(e -> e.type() == BuildError.ErrorType.INCLUDE_CYCLE);
        BuildError cycle = result.errors().stream()
                .filter(e -> e.type() == BuildError.ErrorType.INCLUDE_CYCLE).findFirst().orElseThrow();
        assertThat(cycle.message()).contains("a").contains("b").contains("c");
    }

    @Test
    void selfIncludeIsCycle() {
        var docs = parse("a.md", "@include a.md");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).anyMatch(e -> e.type() == BuildError.ErrorType.INCLUDE_CYCLE);
    }

    @Test
    void transitiveDownstreamPropagation() {
        // a -> b -> c (include), d -> c (reference); changing c affects a, b, d
        var docs = parse(
                "a.md", "@include b.md",
                "b.md", "@include c.md",
                "c.md", "x",
                "d.md", "[x](c.md)");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isFalse();
        DependencyGraph graph = result.graph();
        DocId c = DocId.fromPath("c.md");
        assertThat(graph.affectedDownstream(c))
                .containsExactlyInAnyOrder(DocId.fromPath("a.md"), DocId.fromPath("b.md"), DocId.fromPath("d.md"));
    }

    @Test
    void rootRelativeAndParentRelativeResolution() {
        var docs = parse(
                "guide/a.md", "@include /shared/notice.md\n@include ../shared/other.md",
                "shared/notice.md", "n",
                "shared/other.md", "o");
        var result = builder(PipelineProperties.MissingRefPolicy.ERROR).build(docs);
        assertThat(result.hasErrors()).isFalse();
        DocId a = DocId.fromPath("guide/a.md");
        assertThat(result.graph().includeEdges().get(a)).hasSize(2);
    }
}
