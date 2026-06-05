package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindControllerFlowsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public FindControllerFlowsTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "findControllerFlows"; }
    @Override public String description() {
        return "For every @RestController/@Controller endpoint (and @*Mapping methods), return the " +
               "static execution flow tree starting from that endpoint.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addInt(schema, "maxDepth", "Maximum trace depth per endpoint (1-15).", 5);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        var flows = engine.entries().findControllerFlows(integer(args, "maxDepth", 5));
        return M.valueToTree(flows);
    }
}
