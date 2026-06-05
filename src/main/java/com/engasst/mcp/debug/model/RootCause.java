package com.engasst.mcp.debug.model;

import java.util.ArrayList;
import java.util.List;

/** Deterministic root-cause hypothesis emitted by {@code RootCauseEngine}. */
public final class RootCause {
    public final String category;            // e.g. CLASSPATH_MISMATCH, NULL_DEREFERENCE
    public final String summary;             // one-line explanation
    public final double confidence;          // 0..1
    public final List<String> suggestedSteps = new ArrayList<>();
    public final List<String> evidence = new ArrayList<>();

    public RootCause(String category, String summary, double confidence) {
        this.category = category;
        this.summary = summary;
        this.confidence = confidence;
    }
}
