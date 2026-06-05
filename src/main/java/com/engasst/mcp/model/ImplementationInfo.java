package com.engasst.mcp.model;

public record ImplementationInfo(
        String fullyQualifiedName,
        String relation,    // "implements" | "extends"
        String file,
        int line
) {}
