package com.engasst.mcp.server;

import com.engasst.mcp.tools.SearchCodeTool;
import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.services.CodeSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void initializeAndListTools(@TempDir Path tmp) throws Exception {
        var cfg = ServerConfig.load(tmp);
        var registry = new ToolRegistry();
        registry.register(new SearchCodeTool(new CodeSearchService(new RepoWalker(cfg), cfg)));

        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"shutdown"}
                """;

        var in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        new McpServer(registry, in, out).run();

        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertEquals(3, lines.length);

        JsonNode init = M.readTree(lines[0]);
        assertEquals("2.0", init.get("jsonrpc").asText());
        assertEquals(1, init.get("id").asInt());
        assertTrue(init.get("result").get("capabilities").has("tools"));

        JsonNode list = M.readTree(lines[1]);
        JsonNode tools = list.get("result").get("tools");
        assertEquals(1, tools.size());
        assertEquals("searchCode", tools.get(0).get("name").asText());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
    }

    @Test
    void toolsCallReturnsContent(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "hello world");
        var cfg = ServerConfig.load(tmp);
        var registry = new ToolRegistry();
        registry.register(new SearchCodeTool(new CodeSearchService(new RepoWalker(cfg), cfg)));

        String input = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"searchCode","arguments":{"query":"hello"}}}
                {"jsonrpc":"2.0","id":2,"method":"shutdown"}
                """;
        var in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        new McpServer(registry, in, out).run();

        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        JsonNode resp = M.readTree(lines[0]);
        assertFalse(resp.get("result").get("isError").asBoolean());
        String text = resp.get("result").get("content").get(0).get("text").asText();
        assertTrue(text.contains("a.txt"));
    }
}
