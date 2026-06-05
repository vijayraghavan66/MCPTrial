package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.JavaIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindCallersTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final JavaIndexService index;

    public FindCallersTool(JavaIndexService index) { this.index = index; }

    @Override public String name() { return "findCallers"; }
    @Override public String description() {
        return "Find call sites of a specific method on a specific class. "
             + "Uses JavaParser symbol resolution where possible, with an import-aware "
             + "heuristic fallback. Each result is labelled \"ast\" or \"heuristic\".";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "class", "Target class simple name or fully qualified name.", true);
        addString(schema, "method", "Target method name.", true);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(index.findCallers(
                str(args, "class", null),
                str(args, "method", null)));
    }
}
