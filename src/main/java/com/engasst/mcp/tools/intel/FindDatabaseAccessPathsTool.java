package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class FindDatabaseAccessPathsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public FindDatabaseAccessPathsTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "findDatabaseAccessPaths"; }
    @Override public String description() {
        return "For every service entry point, return paths that reach a persistence sink. " +
               "Sinks are: classes carrying role REPOSITORY / JPA_REPOSITORY / ENTITY, or known JDBC/JPA " +
               "template types (EntityManager, JdbcTemplate, Connection, PreparedStatement, MongoTemplate, ...).";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addInt(schema, "maxDepth", "Maximum trace depth per entry (1-15).", 6);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        var paths = engine.entries().findDatabaseAccessPaths(integer(args, "maxDepth", 6));
        return M.valueToTree(paths);
    }
}
