package com.engasst.mcp.intel.model;

public enum EdgeKind {
    EXTENDS,
    IMPLEMENTS,
    CALLS,
    STATIC_CALL,
    FIELD_READ,
    FIELD_WRITE,
    NEW,
    ANNOTATED_WITH,
    THROWS
}
