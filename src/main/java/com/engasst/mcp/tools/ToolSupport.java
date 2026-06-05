package com.engasst.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Small helpers shared by tool adapters to avoid repetition when reading
 * args and building JSON Schema fragments.
 */
public final class ToolSupport {

    private ToolSupport() {}

    public static String str(JsonNode args, String field, String def) {
        JsonNode v = args.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }

    public static boolean bool(JsonNode args, String field, boolean def) {
        JsonNode v = args.get(field);
        return v == null || v.isNull() ? def : v.asBoolean(def);
    }

    public static int integer(JsonNode args, String field, int def) {
        JsonNode v = args.get(field);
        return v == null || v.isNull() ? def : v.asInt(def);
    }

    public static List<String> stringList(JsonNode args, String field) {
        JsonNode v = args.get(field);
        if (v == null || v.isNull() || !v.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : v) out.add(n.asText());
        return out;
    }

    /** Define a string property in a JSON Schema object. */
    public static void addString(ObjectNode schema, String name, String description, boolean required) {
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode p = props.putObject(name);
        p.put("type", "string");
        p.put("description", description);
        if (required) ((ArrayNode) schema.get("required")).add(name);
    }

    public static void addBool(ObjectNode schema, String name, String description, boolean defaultValue) {
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode p = props.putObject(name);
        p.put("type", "boolean");
        p.put("description", description);
        p.put("default", defaultValue);
    }

    public static void addInt(ObjectNode schema, String name, String description, int defaultValue) {
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode p = props.putObject(name);
        p.put("type", "integer");
        p.put("description", description);
        p.put("default", defaultValue);
    }

    public static void addStringArray(ObjectNode schema, String name, String description) {
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode p = props.putObject(name);
        p.put("type", "array");
        p.putObject("items").put("type", "string");
        p.put("description", description);
    }
}
