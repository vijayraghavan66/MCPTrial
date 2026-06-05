package com.engasst.mcp.tools.debug;

import com.engasst.mcp.debug.DebugEngine;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.addString;
import static com.engasst.mcp.tools.ToolSupport.str;

public final class GenerateInvestigationReportTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private final DebugEngine debug;
    public GenerateInvestigationReportTool(DebugEngine debug) { this.debug = debug; }

    @Override public String name() { return "generateInvestigationReport"; }
    @Override public String description() {
        return "Generate a structured investigation report. If 'stackTrace' is provided, " +
               "analyse it; otherwise summarise the recent failure history of this workspace.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "stackTrace", "Optional stack trace to analyse.", false);
    }
    @Override public JsonNode execute(JsonNode args) throws Exception {
        InvestigationReport rep = debug.generateInvestigationReport(str(args, "stackTrace", ""));
        return M.valueToTree(debug.toMap(rep));
    }
}
