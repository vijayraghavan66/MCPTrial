package com.engasst.mcp.intel.model;

public final class EdgeRow {
    public long repoId;
    public long srcSymbolId;
    /** Transient: pipeline back-fills {@link #srcSymbolId} from this. */
    public transient SymbolRow srcRef;
    public Long dstSymbolId;     // null when unresolved
    public String dstFqn;        // class FQN of target (always set for resolvable kinds)
    public String dstMember;     // method or field name when applicable
    public EdgeKind kind;
    public Long fileId;
    public Integer line;
    public String resolution = "unresolved"; // "ast" | "heuristic" | "unresolved"
    /**
     * For CALLS/STATIC_CALL/NEW: comma-joined erased simple param type names
     * resolved from the argument list (when the symbol solver succeeded).
     * Empty string == no-arg, {@code null} == unknown. Used by the Linker to
     * pick the right overload.
     */
    public String paramTypes;
}
