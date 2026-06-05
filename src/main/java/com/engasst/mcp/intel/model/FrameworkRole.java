package com.engasst.mcp.intel.model;

/**
 * High-level role inferred from annotations / supertypes. Stored on the
 * symbol so entry-point queries are O(index lookup), not O(scan).
 */
public enum FrameworkRole {
    // Spring
    REST_CONTROLLER,
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    COMPONENT,
    CONFIGURATION,
    SCHEDULED,
    EVENT_LISTENER,
    HTTP_ENDPOINT,       // per-method: @GetMapping/@PostMapping/@RequestMapping
    // Persistence
    ENTITY,
    JPA_REPOSITORY,
    // Servlet stack
    SERVLET,
    FILTER,
    LISTENER,
    // Messaging
    MESSAGE_LISTENER
}
