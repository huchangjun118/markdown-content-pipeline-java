package com.github.huchangjun118.markdowncontentpipelinejava.model;

/** Kind of build error, stable for clients. */
public enum ErrorKind {
    PARSE_ERROR,
    CYCLIC_REFERENCE,
    MISSING_REFERENCE,
    RENDER_ERROR,
    IO_ERROR
}
