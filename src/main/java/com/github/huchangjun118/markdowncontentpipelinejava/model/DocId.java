package com.github.huchangjun118.markdowncontentpipelinejava.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Locale;

/**
 * Stable identifier of a document, derived deterministically from its source path.
 * The id never changes as long as the source path stays the same.
 */
@JsonDeserialize(using = DocIdDeserializer.class)
public record DocId(String value) implements Comparable<DocId> {

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @JsonCreator
    public static DocId of(String value) {
        return new DocId(value);
    }

    public static DocId fromPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        if (normalized.endsWith(".md")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        String[] segments = normalized.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            String slug = segments[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
            slug = slug.replaceAll("^-+|-+$", "");
            if (slug.isEmpty()) slug = "_";
            sb.append(slug);
        }
        return new DocId(sb.toString());
    }

    @Override
    public int compareTo(DocId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
