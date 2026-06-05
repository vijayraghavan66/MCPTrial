package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindDependencyPathTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public FindDependencyPathTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "findDependencyPath"; }
    @Override public String description() {
        return "Find the shortest class-to-class dependency path through CALLS / STATIC_CALL / NEW / " +
               "EXTENDS / IMPLEMENTS edges. Returns the ordered hops with the file and line of one " +
               "exemplar edge per hop, or an empty array when no path exists.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "sourceClass", "Starting class FQN or simple name.", true);
        addString(schema, "targetClass", "Target class FQN or simple name.", true);
        addInt(schema, "maxDepth", "Maximum number of hops to consider (1-12).", 8);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        List<?> hops = engine.trace().findDependencyPath(
                str(args, "sourceClass", null),
                str(args, "targetClass", null),
                integer(args, "maxDepth", 8));
        ObjectNode r = M.createObjectNode();
        r.put("hops", hops.size());
        ArrayNode arr = r.putArray("path");
        for (Object h : hops) arr.add(M.valueToTree(h));
        return r;
    }
}
