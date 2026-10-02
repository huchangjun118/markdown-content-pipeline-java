package com.github.huchangjun118.markdowncontentpipelinejava.model;

/** A single observed change to a source file. */
public record FileChange(String id, ChangeType type) {
}
