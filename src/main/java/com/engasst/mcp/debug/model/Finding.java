package com.engasst.mcp.debug.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scored, structured observation produced by the engine. Findings are
 * ranked by {@link #score} (0..100) before being emitted into the report.
 */
public final class Finding {
    public enum Kind {
        RELEVANT_METHOD,
        EXECUTION_PATH,
        RECENT_CHANGE,
        REGRESSION_CANDIDATE,
        DEPENDENCY_PATH,
        HISTORICAL_FAILURE,
        EXTERNAL_FRAME
    }

    public final Kind kind;
    public final String title;
    public final double score;                       // 0..100
    public final Map<String, Object> data = new LinkedHashMap<>();
    public final Map<String, Double> scoreBreakdown = new LinkedHashMap<>();

    public Finding(Kind kind, String title, double score) {
        this.kind = kind;
        this.title = title;
        this.score = score;
    }

    public Finding with(String key, Object value) { data.put(key, value); return this; }
    public Finding withScore(String factor, double weight) { scoreBreakdown.put(factor, weight); return this; }
}
