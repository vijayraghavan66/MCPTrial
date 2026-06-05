package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.JavaIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindMethodTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final JavaIndexService index;

    public FindMethodTool(JavaIndexService index) { this.index = index; }

    @Override public String name() { return "findMethod"; }
    @Override public String description() {
        return "Find methods by name (optionally constrained to a class). "
             + "Returns declaring class FQN, signature, file path, and line.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "method", "Method name (or substring when exact=false).", true);
        addString(schema, "class", "Optional class simple name or FQN filter.", false);
        addBool(schema, "exact", "Require an exact case-sensitive match on method name.", false);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(index.findMethod(
                str(args, "method", null),
                str(args, "class", null),
                bool(args, "exact", false)));
    }
}
