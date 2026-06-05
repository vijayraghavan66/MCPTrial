package com.engasst.mcp.model;

public record ClassInfo(
        String simpleName,
        String packageName,
        String fullyQualifiedName,
        String kind,         // class | interface | enum | record | annotation
        String file,
        int line,
        String declaration   // first line of the class declaration
) {}
