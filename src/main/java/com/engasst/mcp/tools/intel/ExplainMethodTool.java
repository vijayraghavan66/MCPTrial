package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class ExplainMethodTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public ExplainMethodTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "explainMethod"; }
    @Override public String description() {
        return "Return a structured description of a method: signature, annotations, callers, callees, " +
               "field reads/writes and throws. Accepts either a numeric methodId from a previous query " +
               "or (className, methodName).";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addInt(schema, "methodId", "Numeric symbol id from a previous tool result.", 0);
        addString(schema, "className", "FQN or simple class name (used when methodId is absent).", false);
        addString(schema, "methodName", "Method name (used when methodId is absent).", false);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        int idArg = integer(args, "methodId", 0);
        Optional<Map<String, Object>> result;
        if (idArg > 0) {
            result = engine.explain().explainById(idArg);
        } else {
            result = engine.explain().explain(
                    str(args, "className", null),
                    str(args, "methodName", null));
        }
        return result.<JsonNode>map(M::valueToTree)
                .orElseGet(() -> {
                    ObjectNode r = M.createObjectNode();
                    r.put("found", false);
                    return r;
                });
    }
}
