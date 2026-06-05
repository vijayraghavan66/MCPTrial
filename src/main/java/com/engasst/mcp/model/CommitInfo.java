package com.engasst.mcp.model;

public record CommitInfo(
        String hash,
        String shortHash,
        String author,
        String email,
        String date,        // ISO-8601
        String message
) {}
