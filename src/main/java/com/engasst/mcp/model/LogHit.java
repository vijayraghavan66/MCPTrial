package com.engasst.mcp.model;

public record LogHit(
        String file,
        int line,
        String timestamp,   // best-effort extracted timestamp, may be null
        String entry
) {}
