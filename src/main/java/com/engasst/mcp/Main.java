package com.engasst.mcp;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.debug.DebugEngine;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.server.McpServer;
import com.engasst.mcp.server.ToolRegistry;
import com.engasst.mcp.services.CodeSearchService;
import com.engasst.mcp.services.GitService;
import com.engasst.mcp.services.JavaIndexService;
import com.engasst.mcp.services.LogSearchService;
import com.engasst.mcp.tools.*;
import com.engasst.mcp.tools.debug.*;
import com.engasst.mcp.tools.intel.*;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Main {

    public static void main(String[] args) throws Exception {
        Path workspace = resolveWorkspace(args);
        ServerConfig config = ServerConfig.load(workspace);

        RepoWalker walker = new RepoWalker(config);
        ParsedFileCache parsedCache = new ParsedFileCache();

        CodeSearchService codeSearch = new CodeSearchService(walker, config);
        JavaIndexService javaIndex = new JavaIndexService(walker, parsedCache, config);
        GitService git = new GitService(config);
        LogSearchService logs = new LogSearchService(walker, config);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new SearchCodeTool(codeSearch));
        registry.register(new FindClassTool(javaIndex));
        registry.register(new FindMethodTool(javaIndex));
        registry.register(new FindCallersTool(javaIndex));
        registry.register(new FindImplementationsTool(javaIndex));
        registry.register(new GitHistoryTool(git));
        registry.register(new CompareCommitsTool(git));
        registry.register(new SearchLogsTool(logs));

        // Code Intelligence Engine: persistent index + graph queries.
        IntelEngine intel = new IntelEngine(config, walker, parsedCache);
        Runtime.getRuntime().addShutdownHook(new Thread(intel::close, "intel-shutdown"));
        registry.register(new BuildIndexTool(intel));
        registry.register(new GetIndexStatusTool(intel));
        registry.register(new TraceExecutionFlowTool(intel));
        registry.register(new ExplainMethodTool(intel));
        registry.register(new FindDependencyPathTool(intel));
        registry.register(new FindServiceEntryPointsTool(intel));
        registry.register(new FindControllerFlowsTool(intel));
        registry.register(new FindDatabaseAccessPathsTool(intel));

        // Debug Intelligence Engine: stack-trace + log analysis on top of intel + git.
        DebugEngine debug = new DebugEngine(intel, git, logs);
        registry.register(new AnalyzeStackTraceTool(debug));
        registry.register(new AnalyzeLogFileTool(debug));
        registry.register(new InvestigateExceptionTool(debug));
        registry.register(new InvestigateFailureTool(debug));
        registry.register(new GenerateInvestigationReportTool(debug));

        McpServer server = new McpServer(registry, System.in, System.out);
        server.run();
    }

    private static Path resolveWorkspace(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--workspace".equals(args[i]) || "-w".equals(args[i])) {
                return Paths.get(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        String env = System.getenv("MCP_WORKSPACE");
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath().normalize();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }
}
