package com.github.huchangjun118.markdowncontentpipelinejava.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Accepts both the canonical string form and the legacy object form
 * {@code {"value": "..."}} when deserializing a {@link DocId}.
 */
public class DocIdDeserializer extends JsonDeserializer<DocId> {

    @Override
    public DocId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node.isTextual()) {
            return DocId.of(node.asText());
        }
        JsonNode value = node.get("value");
        if (value != null && value.isTextual()) {
            return DocId.of(value.asText());
        }
        return DocId.of(node.asText());
    }
}
