package com.github.huchangjun118.markdowncontentpipelinejava.model;

/**
 * A source file discovered during a scan.
 */
public record SourceFile(String relativePath, String sha256, String content) {
}
