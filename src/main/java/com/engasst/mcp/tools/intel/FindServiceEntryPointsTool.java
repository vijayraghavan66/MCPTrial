package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class FindServiceEntryPointsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public FindServiceEntryPointsTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "findServiceEntryPoints"; }
    @Override public String description() {
        return "List every externally-reachable entry method in the indexed repo: REST/MVC controllers, " +
               "@Scheduled jobs, @EventListener / @JmsListener / @KafkaListener handlers, Servlets, " +
               "Filters and Listeners.";
    }
    @Override public void inputSchema(ObjectNode schema) { /* no args */ }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(engine.entries().findServiceEntryPoints());
    }
}
