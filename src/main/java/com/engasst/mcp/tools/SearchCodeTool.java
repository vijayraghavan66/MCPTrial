package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.CodeSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class SearchCodeTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final CodeSearchService service;

    public SearchCodeTool(CodeSearchService service) { this.service = service; }

    @Override public String name() { return "searchCode"; }

    @Override public String description() {
        return "Recursively search the workspace for text or regex matches. "
             + "Returns file paths, line numbers, and snippets. Skips build/binary directories.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "query", "Text or regex to search for.", true);
        addBool(schema, "regex", "Treat query as a regular expression.", false);
        addBool(schema, "caseSensitive", "Whether the search is case-sensitive.", false);
        addStringArray(schema, "extensions", "Optional list of file extensions to restrict to (without dots).");
        addString(schema, "path", "Optional sub-path under the workspace root to search.", false);
        addInt(schema, "maxResults", "Maximum number of hits to return.", 200);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        var hits = service.search(
                str(args, "query", null),
                bool(args, "regex", false),
                bool(args, "caseSensitive", false),
                stringList(args, "extensions"),
                str(args, "path", null),
                integer(args, "maxResults", 200));
        return M.valueToTree(hits);
    }
}
