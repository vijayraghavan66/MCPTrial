package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.GitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class GitHistoryTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final GitService git;

    public GitHistoryTool(GitService git) { this.git = git; }

    @Override public String name() { return "gitHistory"; }
    @Override public String description() {
        return "Return commit history affecting a file (or the whole repo if no file is given).";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "file", "Repo-relative file path. Omit for full-repo history.", false);
        addInt(schema, "maxCount", "Maximum number of commits to return.", 50);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(git.history(
                str(args, "file", null),
                integer(args, "maxCount", 50)));
    }
}
