package com.github.huchangjun118.markdowncontentpipelinejava.model;

/** How a reference to a non-existent target is handled. */
public enum MissingRefPolicy {
    /** The referencing document fails with a located error (default). */
    FAIL,
    /** The reference is replaced with a deterministic placeholder; build continues. */
    PLACEHOLDER,
    /** The reference is dropped silently; a warning is recorded. */
    IGNORE
}
