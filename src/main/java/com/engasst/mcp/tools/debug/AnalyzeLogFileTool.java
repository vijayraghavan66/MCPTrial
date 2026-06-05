package com.engasst.mcp.tools.debug;

import com.engasst.mcp.debug.DebugEngine;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.addInt;
import static com.engasst.mcp.tools.ToolSupport.addString;
import static com.engasst.mcp.tools.ToolSupport.integer;
import static com.engasst.mcp.tools.ToolSupport.str;

public final class AnalyzeLogFileTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private final DebugEngine debug;
    public AnalyzeLogFileTool(DebugEngine debug) { this.debug = debug; }

    @Override public String name() { return "analyzeLogFile"; }
    @Override public String description() {
        return "Read a log file from the workspace, extract every stack trace, and " +
               "produce a combined investigation report aggregating findings across them.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "logFile", "Workspace-relative path to the log file.", true);
        addInt(schema, "maxStacks", "Maximum number of stack traces to analyse.", 5);
    }
    @Override public JsonNode execute(JsonNode args) throws Exception {
        InvestigationReport rep = debug.analyzeLogFile(
                str(args, "logFile", null),
                integer(args, "maxStacks", 5));
        return M.valueToTree(debug.toMap(rep));
    }
}
