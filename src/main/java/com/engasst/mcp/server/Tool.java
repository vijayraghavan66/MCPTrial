package com.engasst.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An MCP tool. Implementations are pure adapters over a service layer:
 * they translate JSON arguments into typed calls and shape results back into
 * JSON. They must be stateless and thread-safe.
 */
public interface Tool {

    /** Unique tool name exposed to MCP clients (snake_case or camelCase). */
    String name();

    /** Human-readable description used by LLM tool selection. */
    String description();

    /**
     * JSON Schema (draft-07 subset) describing the tool's input arguments.
     * Implementations should populate the provided ObjectNode in-place.
     */
    void inputSchema(ObjectNode schema);

    /**
     * Execute the tool. Returns a JsonNode that will be serialized into
     * the MCP {@code content} response (wrapped as a text content item).
     * Throw to surface a tool error to the client.
     */
    JsonNode execute(JsonNode arguments) throws Exception;
}
