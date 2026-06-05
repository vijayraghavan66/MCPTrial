package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

public final class GetIndexStatusTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public GetIndexStatusTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "getIndexStatus"; }
    @Override public String description() {
        return "Return the current phase and counters of the persistent index for the workspace repository.";
    }
    @Override public void inputSchema(ObjectNode schema) {
        // no arguments
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        Optional<Long> id = engine.store().repoIdByPath(engine.workspaceRoot().toString());
        if (id.isEmpty()) {
            ObjectNode r = M.createObjectNode();
            r.put("phase", "NOT_INDEXED");
            r.put("hint", "Call buildIndex first.");
            return r;
        }
        return M.valueToTree(engine.store().status(id.get()).orElseThrow());
    }
}
