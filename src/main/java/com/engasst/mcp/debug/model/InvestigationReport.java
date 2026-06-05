package com.engasst.mcp.debug.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured investigation report. Consumed by the LLM as the source of
 * truth — no LLM reasoning is involved in producing it.
 */
public final class InvestigationReport {
    public final String inputKind;                       // STACK_TRACE | LOG_FILE | EXCEPTION | METHOD
    public final String input;                           // short echo of what was analysed
    public final long generatedAt = System.currentTimeMillis();

    public ParsedStackTrace parsedStack;                 // nullable
    public final List<RootCause> rootCauses = new ArrayList<>();
    public final List<Finding> findings = new ArrayList<>();
    public final Map<String, Object> stats = new LinkedHashMap<>();

    public double overallConfidence;                     // 0..1

    public InvestigationReport(String inputKind, String input) {
        this.inputKind = inputKind;
        this.input = input;
    }
}
