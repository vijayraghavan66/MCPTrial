package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.intel.query.TraceService;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class TraceExecutionFlowTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public TraceExecutionFlowTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "traceExecutionFlow"; }
    @Override public String description() {
        return "Trace the static execution flow from a starting method by following CALLS, STATIC_CALL and NEW edges. " +
               "Returns a tree of methods (each annotated with framework role, file and line). " +
               "Powered by the persistent index — no re-parsing.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "className", "Fully qualified or simple class name of the entry method.", true);
        addString(schema, "methodName", "Method name on that class.", true);
        addInt(schema, "maxDepth", "Maximum recursion depth (1-25).", 6);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        TraceService trace = engine.trace();
        List<TraceService.FlowNode> nodes = trace.traceExecutionFlow(
                str(args, "className", null),
                str(args, "methodName", null),
                integer(args, "maxDepth", 6));
        Map<String, Object> tree = trace.shapeFlowAsTree(nodes);
        ObjectNode r = M.createObjectNode();
        r.put("nodeCount", nodes.size());
        r.set("flow", M.valueToTree(tree));
        return r;
    }
}
