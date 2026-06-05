package com.engasst.mcp.intel;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.pipeline.IndexingPipeline;
import com.engasst.mcp.intel.query.EntryPointService;
import com.engasst.mcp.intel.query.ExplainService;
import com.engasst.mcp.intel.query.TraceService;
import com.engasst.mcp.intel.store.IndexStore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Composition root for the Code Intelligence Engine. Owns the SQLite
 * database, the indexing pipeline and all query services. Lives for the
 * lifetime of the MCP server process.
 */
public final class IntelEngine implements AutoCloseable {

    private final Database database;
    private final IndexStore store;
    private final IndexingPipeline pipeline;
    private final TraceService trace;
    private final ExplainService explain;
    private final EntryPointService entries;
    private final Path workspaceRoot;

    public IntelEngine(ServerConfig config, RepoWalker walker, ParsedFileCache parsedCache)
            throws IOException, SQLException {
        this.workspaceRoot = config.workspaceRoot();
        Path dbFile = workspaceRoot.resolve(".mcp").resolve("intel.db");
        this.database = Database.open(dbFile);
        this.store    = new IndexStore(database);
        this.pipeline = new IndexingPipeline(walker, config, store, parsedCache);
        this.trace    = new TraceService(store);
        this.explain  = new ExplainService(store, trace);
        this.entries  = new EntryPointService(store, trace);
    }

    public Path workspaceRoot()           { return workspaceRoot; }
    public IndexStore store()             { return store; }
    public IndexingPipeline pipeline()    { return pipeline; }
    public TraceService trace()           { return trace; }
    public ExplainService explain()       { return explain; }
    public EntryPointService entries()    { return entries; }

    @Override public void close() { database.close(); }
}
