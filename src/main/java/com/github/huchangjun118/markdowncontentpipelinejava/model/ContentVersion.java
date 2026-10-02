package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;
import java.util.Map;

/**
 * An immutable published version of the whole content set.
 */
public record ContentVersion(
        long version,
        Map<String, BuildArtifact> artifacts,
        List<BuildError> errors,
        String trigger,
        long createdAt) {

    /** @return artifact by document id, or null */
    public BuildArtifact artifact(String id) {
        return artifacts.get(id);
    }
}
