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

public final class InvestigateFailureTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private final DebugEngine debug;
    public InvestigateFailureTool(DebugEngine debug) { this.debug = debug; }

    @Override public String name() { return "investigateFailure"; }
    @Override public String description() {
        return "Investigate a specific method as if it were the top of a stack trace. " +
               "Returns its execution flow, recent changes touching it and regression candidates.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "className", "Fully qualified or simple class name.", true);
        addString(schema, "methodName", "Method to investigate.", true);
        addInt(schema, "maxDepth", "Maximum trace depth.", 5);
    }
    @Override public JsonNode execute(JsonNode args) throws Exception {
        InvestigationReport rep = debug.investigateFailure(
                str(args, "className", null),
                str(args, "methodName", null),
                integer(args, "maxDepth", 5));
        return M.valueToTree(debug.toMap(rep));
    }
}
