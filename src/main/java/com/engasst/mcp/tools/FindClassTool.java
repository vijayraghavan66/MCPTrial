package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.JavaIndexService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindClassTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final JavaIndexService index;

    public FindClassTool(JavaIndexService index) { this.index = index; }

    @Override public String name() { return "findClass"; }
    @Override public String description() {
        return "Locate Java classes / interfaces / enums / records by simple name. "
             + "Returns package, FQN, file path, line, and the declaration header.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "name", "Class simple name (or substring when exact=false).", true);
        addBool(schema, "exact", "Require an exact case-sensitive match.", false);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(index.findClass(
                str(args, "name", null),
                bool(args, "exact", false)));
    }
}
