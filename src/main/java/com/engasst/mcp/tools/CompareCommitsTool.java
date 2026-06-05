package com.engasst.mcp.tools;

import com.engasst.mcp.server.Tool;
import com.engasst.mcp.services.GitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class CompareCommitsTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final GitService git;

    public CompareCommitsTool(GitService git) { this.git = git; }

    @Override public String name() { return "compareCommits"; }
    @Override public String description() {
        return "Return per-file diff information between two revisions (sha, tag, branch, or HEAD~N): "
             + "change type, paths, added/deleted line counts, and a truncated unified patch.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "from", "Base revision.", true);
        addString(schema, "to",   "Target revision.", true);
        addInt(schema, "maxPatchLines", "Max patch lines per file to return.", 400);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        return M.valueToTree(git.compare(
                str(args, "from", null),
                str(args, "to", null),
                integer(args, "maxPatchLines", 400)));
    }
}
