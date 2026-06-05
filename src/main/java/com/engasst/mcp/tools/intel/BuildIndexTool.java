package com.engasst.mcp.tools.intel;

import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.intel.model.IndexStatus;
import com.engasst.mcp.server.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.engasst.mcp.tools.ToolSupport.*;

public final class BuildIndexTool implements Tool {

    private static final ObjectMapper M = new ObjectMapper();
    private final IntelEngine engine;

    public BuildIndexTool(IntelEngine engine) { this.engine = engine; }

    @Override public String name() { return "buildIndex"; }
    @Override public String description() {
        return "Build or update the persistent code intelligence index for a repository. " +
               "Incremental by default: only re-parses files whose mtime/sha have changed.";
    }

    @Override public void inputSchema(ObjectNode schema) {
        addString(schema, "repoPath", "Absolute path of the repository to index. Defaults to workspace root.", false);
        addBool(schema, "fullRebuild", "Drop the existing index for this repo before building.", false);
    }

    @Override public JsonNode execute(JsonNode args) throws Exception {
        String pathStr = str(args, "repoPath", null);
        Path repo = pathStr == null || pathStr.isBlank()
                ? engine.workspaceRoot()
                : Paths.get(pathStr).toAbsolutePath().normalize();
        boolean fullRebuild = bool(args, "fullRebuild", false);
        IndexStatus st = engine.pipeline().build(repo, !fullRebuild);
        return M.valueToTree(st);
    }
}
