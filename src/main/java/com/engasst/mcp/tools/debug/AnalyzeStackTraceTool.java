package com.engasst.mcp.tools.debug;

import com.engasst.mcp.debug.DebugEngine;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.addString;
import static com.engasst.mcp.tools.ToolSupport.str;

public final class AnalyzeStackTraceTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private final DebugEngine debug;
    public AnalyzeStackTraceTool(DebugEngine debug) { this.debug = debug; }

    @Override public String name() { return "analyzeStackTrace"; }
    @Override public String description() {
        return "Parse a Java stack trace, correlate frames with the persistent index, " +
               "find recent commits / regression candidates / probable root causes, " +
               "and emit a ranked investigation report. Deterministic — no LLM reasoning.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "stackTrace", "Full stack trace text (multi-line).", true);
    }
    @Override public JsonNode execute(JsonNode args) throws Exception {
        InvestigationReport rep = debug.analyzeStackTrace(str(args, "stackTrace", ""));
        return M.valueToTree(debug.toMap(rep));
    }
}
