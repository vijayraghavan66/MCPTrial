package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.JavaIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindImplementationsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final JavaIndexService index;

    public FindImplementationsTool(JavaIndexService index) { this.index = index; }

    @Override public String name() { return "findImplementations"; }
    @Override public String description() {
        return "Find classes implementing an interface or extending a (possibly abstract) class.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "type", "Interface or class simple name or fully qualified name.", true);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(index.findImplementations(str(args, "type", null)));
    }
}
