package com.engasst.mcp.intel.model;

/**
 * Snapshot of the index build for one repo.
 *
 * <p>Two status fields are reported:
 * <ul>
 *   <li>{@code state}: coarse lifecycle —
 *       {@code IDLE | BUILDING | FAILED | COMPLETED}. The only field readers
 *       should branch on when deciding whether to trust the index.</li>
 *   <li>{@code phase}: fine-grained diagnostic substate —
 *       {@code IDLE | SCANNING | PARSING | LINKING | DONE | ERROR}.
 *       Kept for backward compatibility and progress reporting.</li>
 * </ul>
 */
public record IndexStatus(
        long repoId,
        String rootPath,
        String state,
        String phase,
        int filesTotal,
        int filesChanged,
        int filesDone,
        long symbolsCount,
        long edgesCount,
        Long startedAt,
        Long finishedAt,
        String error
) {}
