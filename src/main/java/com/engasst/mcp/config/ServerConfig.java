package com.engasst.mcp.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Runtime configuration. Resolved from (in priority order):
 *   1. System properties (-Dmcp.foo=...)
 *   2. .mcp/server.properties at workspace root
 *   3. Built-in defaults
 */
public final class ServerConfig {

    private final Path workspaceRoot;
    private final List<String> ignoredDirs;
    private final List<String> textFileExtensions;
    private final long maxFileSizeBytes;
    private final int maxResults;
    private final int astThreadPoolSize;

    private ServerConfig(Path workspaceRoot,
                         List<String> ignoredDirs,
                         List<String> textFileExtensions,
                         long maxFileSizeBytes,
                         int maxResults,
                         int astThreadPoolSize) {
        this.workspaceRoot = workspaceRoot;
        this.ignoredDirs = List.copyOf(ignoredDirs);
        this.textFileExtensions = List.copyOf(textFileExtensions);
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxResults = maxResults;
        this.astThreadPoolSize = astThreadPoolSize;
    }

    public static ServerConfig load(Path workspaceRoot) {
        Properties p = new Properties();
        Path cfg = workspaceRoot.resolve(".mcp").resolve("server.properties");
        if (Files.isRegularFile(cfg)) {
            try (var in = Files.newInputStream(cfg)) {
                p.load(in);
            } catch (Exception ignored) {
                // fall through to defaults
            }
        }

        List<String> ignored = csv(prop(p, "mcp.ignoredDirs",
                ".git,.hg,.svn,.idea,.vscode,target,build,out,bin,node_modules,dist,.gradle,.mvn"));
        List<String> exts = csv(prop(p, "mcp.textExtensions",
                "java,kt,scala,groovy,xml,yml,yaml,properties,json,md,txt,sql,sh,bat,gradle,toml,ini,conf,log"));
        long maxSize = Long.parseLong(prop(p, "mcp.maxFileSizeBytes", "5242880"));   // 5 MB
        int maxResults = Integer.parseInt(prop(p, "mcp.maxResults", "500"));
        int astThreads = Integer.parseInt(prop(p, "mcp.astThreads",
                String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))));

        return new ServerConfig(workspaceRoot, ignored, exts, maxSize, maxResults, astThreads);
    }

    private static String prop(Properties p, String key, String def) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        return p.getProperty(key, def);
    }

    private static List<String> csv(String s) {
        return List.of(s.split("\\s*,\\s*"));
    }

    public Path workspaceRoot()        { return workspaceRoot; }
    public List<String> ignoredDirs()  { return ignoredDirs; }
    public List<String> textFileExtensions() { return textFileExtensions; }
    public long maxFileSizeBytes()     { return maxFileSizeBytes; }
    public int maxResults()            { return maxResults; }
    public int astThreadPoolSize()     { return astThreadPoolSize; }
}
