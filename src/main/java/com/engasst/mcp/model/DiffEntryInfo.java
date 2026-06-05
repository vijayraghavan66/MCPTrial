package com.engasst.mcp.model;

public record DiffEntryInfo(
        String changeType,   // ADD | MODIFY | DELETE | RENAME | COPY
        String oldPath,
        String newPath,
        int addedLines,
        int deletedLines,
        String patch         // truncated unified diff
) {}
