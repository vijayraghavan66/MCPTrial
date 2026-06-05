package com.engasst.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MCP server speaking JSON-RPC 2.0 over stdio with
 * newline-delimited messages.
 *
 * Supported methods:
 *   - initialize
 *   - tools/list
 *   - tools/call
 *   - ping
 *   - shutdown (graceful)
 *
 * Notifications (no response): notifications/initialized, notifications/cancelled.
 *
 * stdout is reserved for protocol traffic. All diagnostic output must go to stderr.
 */
public final class McpServer {

    private static final Logger LOG = LoggerFactory.getLogger(McpServer.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "java-engineering-mcp";
    private static final String SERVER_VERSION = "0.1.0";

    private final ToolRegistry registry;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean running = true;

    public McpServer(ToolRegistry registry, InputStream in, OutputStream out) {
        this.registry = registry;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    public void run() throws Exception {
        LOG.info("MCP server starting (stdio)");
        String line;
        while (running && (line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonNode req = json.readTree(line);
                handle(req);
            } catch (Exception ex) {
                LOG.error("Failed to handle message: {}", line, ex);
                writeError(null, -32700, "Parse error: " + ex.getMessage(), null);
            }
        }
        LOG.info("MCP server stopped");
    }

    private void handle(JsonNode req) throws Exception {
        String method = text(req, "method");
        JsonNode id = req.get("id");
        boolean isNotification = (id == null || id.isNull());

        if (method == null) {
            if (!isNotification) writeError(id, -32600, "Missing method", null);
            return;
        }

        switch (method) {
            case "initialize"             -> writeResult(id, initializeResult());
            case "tools/list"             -> writeResult(id, toolsList());
            case "tools/call"             -> handleToolCall(id, req.get("params"));
            case "ping"                   -> writeResult(id, json.createObjectNode());
            case "shutdown"               -> { writeResult(id, json.createObjectNode()); running = false; }
            case "notifications/initialized",
                 "notifications/cancelled" -> { /* notifications: ignore */ }
            default -> {
                if (!isNotification) writeError(id, -32601, "Method not found: " + method, null);
            }
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = json.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : registry.all()) {
            ObjectNode entry = arr.addObject();
            entry.put("name", t.name());
            entry.put("description", t.description());
            ObjectNode schema = entry.putObject("inputSchema");
            schema.put("type", "object");
            schema.putObject("properties");
            schema.putArray("required");
            t.inputSchema(schema);
        }
        return r;
    }

    private void handleToolCall(JsonNode id, JsonNode params) throws Exception {
        if (params == null) { writeError(id, -32602, "Missing params", null); return; }
        String name = text(params, "name");
        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) args = json.createObjectNode();
        var tool = registry.get(name);
        if (tool.isEmpty()) { writeError(id, -32602, "Unknown tool: " + name, null); return; }

        ObjectNode result = json.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            JsonNode payload = tool.get().execute(args);
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", json.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
            result.put("isError", false);
        } catch (Exception ex) {
            LOG.warn("Tool {} failed", name, ex);
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", "Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            result.put("isError", true);
        }
        writeResult(id, result);
    }

    private void writeResult(JsonNode id, JsonNode result) throws Exception {
        ObjectNode env = json.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id == null) env.putNull("id"); else env.set("id", id);
        env.set("result", result);
        send(env);
    }

    private void writeError(JsonNode id, int code, String message, JsonNode data) throws Exception {
        ObjectNode env = json.createObjectNode();
        env.put("jsonrpc", "2.0");
        if (id == null) env.putNull("id"); else env.set("id", id);
        ObjectNode err = env.putObject("error");
        err.put("code", code);
        err.put("message", message);
        if (data != null) err.set("data", data);
        send(env);
    }

    private synchronized void send(ObjectNode env) throws Exception {
        String s = json.writeValueAsString(env);
        out.write(s);
        out.write('\n');
        out.flush();
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
}
