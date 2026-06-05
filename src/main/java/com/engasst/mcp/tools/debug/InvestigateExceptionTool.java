package com.engasst.mcp.tools.debug;

import com.engasst.mcp.debug.DebugEngine;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.addString;
import static com.engasst.mcp.tools.ToolSupport.str;

public final class InvestigateExceptionTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private final DebugEngine debug;
    public InvestigateExceptionTool(DebugEngine debug) { this.debug = debug; }

    @Override public String name() { return "investigateException"; }
    @Override public String description() {
        return "Investigate a specific exception class. Returns root-cause hypotheses, " +
               "prior occurrences in logs and historical failures.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "exceptionClass", "Fully qualified exception class name.", true);
    }
    @Override public JsonNode execute(JsonNode args) throws Exception {
        InvestigationReport rep = debug.investigateException(str(args, "exceptionClass", null));
        return M.valueToTree(debug.toMap(rep));
    }
}
