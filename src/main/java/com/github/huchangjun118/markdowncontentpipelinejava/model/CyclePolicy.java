package com.github.huchangjun118.markdowncontentpipelinejava.model;

/** How a document participating in an include cycle is handled. */
public enum CyclePolicy {
    /** Every document participating in a cycle fails with a located error (default). */
    FAIL,
    /** Includes that would close a cycle are rendered with a deterministic error marker. */
    BREAK,
    /** A fixed number of include levels are rendered, deeper levels are marked. */
    BOUNDED
}
