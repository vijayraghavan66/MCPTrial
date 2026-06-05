package com.engasst.mcp.model;

public record CallerInfo(
        String callerClass,       // FQN of class containing the call site
        String callerMethod,      // enclosing method name, or "<init>" / "<static>"
        String file,
        int line,
        String snippet,
        String resolution         // "ast" or "heuristic"
) {}
