package com.github.huchangjun118.markdowncontentpipelinejava.model;

import java.util.List;

/**
 * Summary of a stored version, safe for API exposure.
 */
public record VersionInfo(
        long version,
        int documentCount,
        List<BuildError> errors,
        String trigger,
        long createdAt,
        boolean current) {
}
