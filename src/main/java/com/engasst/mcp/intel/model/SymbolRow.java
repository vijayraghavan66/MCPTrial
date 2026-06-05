package com.engasst.mcp.intel.model;

/** Mutable on purpose: the extractor fills these fields incrementally. */
public final class SymbolRow {
    public long id;
    public long repoId;
    public Long fileId;
    public Long parentId;
    /** Transient: pipeline back-fills {@link #parentId} from this after insert. */
    public transient SymbolRow parentRef;
    public SymbolKind kind;
    public String pkg;
    public String simpleName;
    public String fqn;
    public String signature;
    public String returnType;
    public String modifiers;
    public String frameworkRole;
    public Integer startLine;
    public Integer endLine;
    /**
     * For methods and constructors only: comma-joined erased simple param type
     * names (e.g. {@code "String,int"}). Empty string == no-arg. {@code null}
     * for non-callables. Used by the Linker to disambiguate overloads.
     */
    public String paramTypes;
}
