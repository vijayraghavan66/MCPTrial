package com.engasst.mcp.model;

public record MethodInfo(
        String methodName,
        String signature,
        String declaringClass,    // FQN
        String file,
        int line
) {}
