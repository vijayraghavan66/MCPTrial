package com.engasst.mcp.intel.query;

import com.engasst.mcp.intel.store.IndexStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry-point and access-path queries: which classes/methods are reachable
 * from the outside (controllers, scheduled jobs, servlets, listeners) and
 * which call paths terminate at known persistence sinks.
 */
public final class EntryPointService {

    /** Spring Data / JPA / JDBC / JCA classes treated as persistence sinks. */
    private static final Set<String> DB_SINK_TYPES = Set.of(
            "EntityManager", "JdbcTemplate", "NamedParameterJdbcTemplate",
            "Connection", "PreparedStatement", "Statement",
            "Session", "SessionFactory", "MongoTemplate", "ReactiveMongoTemplate",
            "RedisTemplate", "CqlSession", "DataSource", "R2dbcEntityTemplate");
    private static final Set<String> DB_SINK_ROLES = Set.of(
            "REPOSITORY", "JPA_REPOSITORY", "ENTITY");

    public record EntryPoint(long methodId, String classFqn, String method, String signature,
                              String role, String file, Integer line) {}

    public record AccessPath(EntryPoint entry, List<TraceService.FlowNode> flow,
                              String sinkClass, String sinkMethod) {}

    private final IndexStore store;
    private final TraceService trace;

    public EntryPointService(IndexStore store, TraceService trace) {
        this.store = store;
        this.trace = trace;
    }

    // ---------------------------------------------------- service entry pts

    private static final Set<String> ENTRY_ROLES = Set.of(
            "REST_CONTROLLER", "CONTROLLER", "HTTP_ENDPOINT",
            "SCHEDULED", "EVENT_LISTENER", "MESSAGE_LISTENER",
            "SERVLET", "FILTER", "LISTENER");

    public List<EntryPoint> findServiceEntryPoints() throws SQLException {
        // Methods directly carrying an entry role, plus methods of types
        // whose role is itself an entry (e.g. all methods of a SERVLET class).
        String sql = """
                SELECT m.id, t.fqn, m.simple_name, m.signature,
                       COALESCE(m.framework_role, t.framework_role) AS role,
                       files.path, m.start_line
                FROM symbols m
                LEFT JOIN symbols t ON t.id = m.parent_id
                LEFT JOIN files ON files.id = m.file_id
                WHERE m.kind = 'METHOD'
                  AND (m.framework_role IN ('HTTP_ENDPOINT','SCHEDULED','EVENT_LISTENER','MESSAGE_LISTENER')
                    OR (t.framework_role IN ('REST_CONTROLLER','CONTROLLER','SERVLET','FILTER','LISTENER')
                        AND (m.modifiers IS NULL OR m.modifiers NOT LIKE '%private%')))
                ORDER BY t.fqn, m.simple_name
                """;
        return runEntryQuery(sql);
    }

    public List<EntryPoint> findControllerEndpoints() throws SQLException {
        String sql = """
                SELECT m.id, t.fqn, m.simple_name, m.signature,
                       COALESCE(m.framework_role, t.framework_role) AS role,
                       files.path, m.start_line
                FROM symbols m
                JOIN symbols t ON t.id = m.parent_id
                LEFT JOIN files ON files.id = m.file_id
                WHERE m.kind = 'METHOD'
                  AND (t.framework_role IN ('REST_CONTROLLER','CONTROLLER')
                    OR m.framework_role = 'HTTP_ENDPOINT')
                  AND (m.modifiers IS NULL OR m.modifiers NOT LIKE '%private%')
                ORDER BY t.fqn, m.simple_name
                """;
        return runEntryQuery(sql);
    }

    private List<EntryPoint> runEntryQuery(String sql) throws SQLException {
        List<EntryPoint> out = new ArrayList<>();
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new EntryPoint(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5),
                        rs.getString(6), (Integer) rs.getObject(7)));
            }
        }
        return out;
    }

    // -------------------------------------------------- controller -> flow

    public List<Map<String, Object>> findControllerFlows(int maxDepth) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntryPoint ep : findControllerEndpoints()) {
            List<TraceService.FlowNode> flow = trace.traceExecutionFlow(ep.classFqn(), ep.method(), maxDepth);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("controller", ep.classFqn());
            entry.put("endpoint", ep.method());
            entry.put("signature", ep.signature());
            entry.put("file", ep.file());
            entry.put("line", ep.line());
            entry.put("flow", trace.shapeFlowAsTree(flow));
            out.add(entry);
        }
        return out;
    }

    // ------------------------------------------ entry-points -> DB sinks

    public List<AccessPath> findDatabaseAccessPaths(int maxDepth) throws SQLException {
        Set<Long> sinkIds = collectDbSinkMethodIds();
        if (sinkIds.isEmpty()) return List.of();

        List<AccessPath> out = new ArrayList<>();
        for (EntryPoint ep : findServiceEntryPoints()) {
            List<TraceService.FlowNode> flow = trace.traceExecutionFlow(ep.classFqn(), ep.method(), maxDepth);
            for (TraceService.FlowNode n : flow) {
                if (sinkIds.contains(n.symbolId())) {
                    out.add(new AccessPath(ep, flow, n.classFqn(), n.method()));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * IDs of all methods declared on classes considered persistence sinks:
     * known JDBC/JPA template types or anything tagged REPOSITORY / JPA_REPOSITORY / ENTITY.
     */
    private Set<Long> collectDbSinkMethodIds() throws SQLException {
        Set<Long> ids = new java.util.HashSet<>();
        // by simple type name (catches external libs whose source we don't index)
        StringBuilder in = new StringBuilder();
        DB_SINK_TYPES.forEach(t -> in.append(in.length() == 0 ? "?" : ",?"));
        String typeSql =
                "SELECT m.id FROM symbols m JOIN symbols t ON t.id = m.parent_id " +
                "WHERE m.kind = 'METHOD' AND t.simple_name IN (" + in + ")";
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(typeSql)) {
            int i = 1;
            for (String t : DB_SINK_TYPES) ps.setString(i++, t);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
        }
        // by role
        StringBuilder in2 = new StringBuilder();
        DB_SINK_ROLES.forEach(t -> in2.append(in2.length() == 0 ? "?" : ",?"));
        String roleSql =
                "SELECT m.id FROM symbols m JOIN symbols t ON t.id = m.parent_id " +
                "WHERE m.kind = 'METHOD' AND t.framework_role IN (" + in2 + ")";
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(roleSql)) {
            int i = 1;
            for (String t : DB_SINK_ROLES) ps.setString(i++, t);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
        }
        return ids;
    }
}
