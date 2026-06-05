package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.LogSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class SearchLogsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final LogSearchService logs;

    public SearchLogsTool(LogSearchService logs) { this.logs = logs; }

    @Override public String name() { return "searchLogs"; }
    @Override public String description() {
        return "Search .log/.out/.txt files for text or regex patterns. "
             + "Returns matching entries with best-effort extracted timestamps.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "query", "Text or regex pattern to search for.", true);
        addBool(schema, "regex", "Treat query as a regex.", false);
        addBool(schema, "caseSensitive", "Whether the search is case-sensitive.", false);
        addString(schema, "path", "Optional sub-path under the workspace root to search.", false);
        addInt(schema, "maxResults", "Maximum number of hits to return.", 200);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(logs.search(
                str(args, "query", null),
                bool(args, "regex", false),
                bool(args, "caseSensitive", false),
                str(args, "path", null),
                integer(args, "maxResults", 200)));
    }
}
