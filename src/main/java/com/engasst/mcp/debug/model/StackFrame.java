package com.engasst.mcp.debug.model;

/**
 * One parsed Java stack frame. {@code inRepo} and {@code symbolId} are
 * filled in by {@link com.engasst.mcp.debug.correlate.SymbolLocator} after
 * the frame is correlated against the persistent index.
 */
public final class StackFrame {
    public final int index;            // 0 == topmost frame
    public final String classFqn;      // e.g. com.acme.svc.OrderService
    public final String method;        // e.g. submit
    public final String fileHint;      // file name from the frame, may be null
    public final Integer lineNumber;   // null for "Native Method" / unknown

    public Long symbolId;              // populated post-correlation
    public boolean inRepo;             // true if symbol exists in the index
    public String filePath;            // repo-relative file path, when known

    public StackFrame(int index, String classFqn, String method,
                      String fileHint, Integer lineNumber) {
        this.index = index;
        this.classFqn = classFqn;
        this.method = method;
        this.fileHint = fileHint;
        this.lineNumber = lineNumber;
    }

    public String displaySignature() {
        return classFqn + "." + method
                + (fileHint != null ? "(" + fileHint
                    + (lineNumber != null ? ":" + lineNumber : "") + ")"
                    : "");
    }
}
