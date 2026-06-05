package com.engasst.mcp.debug.model;

import java.util.ArrayList;
import java.util.List;

/** A stack trace decomposed into exception chain + frames. */
public final class ParsedStackTrace {
    public final List<ExceptionLink> chain = new ArrayList<>();
    public final List<StackFrame> frames = new ArrayList<>();
    public final String raw;

    public ParsedStackTrace(String raw) { this.raw = raw; }

    public ExceptionLink rootCause() {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1);
    }

    public ExceptionLink primary() {
        return chain.isEmpty() ? null : chain.get(0);
    }

    /** One link in a "Caused by:" chain. */
    public static final class ExceptionLink {
        public final String exceptionFqn;    // e.g. java.lang.NoSuchMethodError
        public final String message;         // may be null

        public ExceptionLink(String exceptionFqn, String message) {
            this.exceptionFqn = exceptionFqn;
            this.message = message;
        }
    }
}
