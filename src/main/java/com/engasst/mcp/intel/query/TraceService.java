package com.engasst.mcp.intel.query;

import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.store.IndexStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-side queries powering the trace / explain / entry-point MCP tools.
 *
 * All graph traversal is expressed as SQL recursive CTEs over {@code edges}
 * so the JVM never materialises the call graph in memory.
 */
public final class TraceService {

    public record FlowNode(
            long symbolId, String classFqn, String method, String signature,
            String role, String file, Integer line, int depth, String parentPath
    ) {}

    public record DependencyHop(
            String fromClass, String toClass, String edgeKind, String file, Integer line
    ) {}

    private final IndexStore store;

    public TraceService(IndexStore store) { this.store = store; }

    // ------------------------------------------------------- traceExecution

    /**
     * Walks CALLS / STATIC_CALL / NEW edges from a starting method up to
     * {@code maxDepth}. The {@code visited path} string keeps the traversal
     * acyclic without per-row Java state.
     */
    public List<FlowNode> traceExecutionFlow(String classFqn, String method, int maxDepth) throws SQLException {
        Long startId = resolveMethodId(classFqn, method).orElse(null);
        if (startId == null) return List.of();
        int depth = Math.max(1, Math.min(maxDepth, 25));

        String sql = """
                WITH RECURSIVE flow(method_id, depth, path) AS (
                    SELECT ?, 0, CAST(? AS TEXT)
                  UNION ALL
                    SELECT e.dst_symbol_id, f.depth + 1,
                           f.path || '>' || CAST(e.dst_symbol_id AS TEXT)
                    FROM edges e
                    JOIN flow f ON e.src_symbol_id = f.method_id
                    WHERE e.kind IN ('CALLS','STATIC_CALL','NEW')
                      AND e.dst_symbol_id IS NOT NULL
                      AND f.depth < ?
                      AND INSTR(f.path, '>' || CAST(e.dst_symbol_id AS TEXT)) = 0
                      AND f.path NOT LIKE CAST(e.dst_symbol_id AS TEXT) || '%'
                )
                SELECT f.method_id, f.depth, f.path,
                       m.simple_name, m.signature, m.framework_role,
                       t.fqn AS class_fqn,
                       files.path AS file_path, m.start_line
                FROM flow f
                JOIN symbols m ON m.id = f.method_id
                LEFT JOIN symbols t ON t.id = m.parent_id
                LEFT JOIN files   ON files.id = m.file_id
                ORDER BY f.depth, m.id
                """;

        List<FlowNode> out = new ArrayList<>();
        Database db = store.db();
        try (Connection c = db.readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, startId);
            ps.setLong(2, startId);
            ps.setInt(3, depth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FlowNode(
                            rs.getLong("method_id"),
                            rs.getString("class_fqn"),
                            rs.getString("simple_name"),
                            rs.getString("signature"),
                            rs.getString("framework_role"),
                            rs.getString("file_path"),
                            (Integer) rs.getObject("start_line"),
                            rs.getInt("depth"),
                            rs.getString("path")));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------- dependencyPath

    /**
     * Shortest class-to-class dependency path using a class-level projection
     * of behavioural edges (CALLS/NEW/STATIC_CALL/EXTENDS/IMPLEMENTS).
     */
    public List<DependencyHop> findDependencyPath(String sourceClass, String targetClass, int maxDepth)
            throws SQLException {
        Long src = resolveTypeId(sourceClass).orElse(null);
        Long dst = resolveTypeId(targetClass).orElse(null);
        if (src == null || dst == null) return List.of();

        int depth = Math.max(1, Math.min(maxDepth, 12));

        // Project method/field edges to their declaring classes. Use a CTE
        // edges_cls that produces (from_class, to_class, kind).
        String sql = """
                WITH RECURSIVE
                    edges_cls(from_cls, to_cls, kind, file_id, line) AS (
                      SELECT COALESCE(src_t.id, e.src_symbol_id) AS from_cls,
                             COALESCE(dst_t.id, e.dst_symbol_id) AS to_cls,
                             e.kind, e.file_id, e.line
                      FROM edges e
                      LEFT JOIN symbols src_m ON src_m.id = e.src_symbol_id
                      LEFT JOIN symbols src_t ON src_t.id = src_m.parent_id
                      LEFT JOIN symbols dst_m ON dst_m.id = e.dst_symbol_id
                      LEFT JOIN symbols dst_t ON dst_t.id = dst_m.parent_id
                      WHERE e.dst_symbol_id IS NOT NULL
                        AND e.kind IN ('CALLS','STATIC_CALL','NEW','EXTENDS','IMPLEMENTS')
                    ),
                    walk(class_id, depth, path) AS (
                      SELECT ?, 0, CAST(? AS TEXT)
                      UNION ALL
                      SELECT ec.to_cls, w.depth+1, w.path || '>' || CAST(ec.to_cls AS TEXT)
                      FROM edges_cls ec
                      JOIN walk w ON ec.from_cls = w.class_id
                      WHERE w.depth < ?
                        AND INSTR('>' || w.path || '>', '>' || CAST(ec.to_cls AS TEXT) || '>') = 0
                    )
                SELECT path FROM walk WHERE class_id = ? ORDER BY depth LIMIT 1
                """;

        Database db = store.db();
        String pathStr = null;
        try (Connection c = db.readConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, src); ps.setLong(2, src); ps.setInt(3, depth); ps.setLong(4, dst);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) pathStr = rs.getString(1);
            }
        }
        if (pathStr == null) return List.of();

        // Expand path ids into typed hops with class FQNs and an exemplar edge per hop.
        String[] ids = pathStr.split(">");
        List<DependencyHop> hops = new ArrayList<>();
        try (Connection c = store.db().readConnection()) {
            for (int i = 0; i < ids.length - 1; i++) {
                long a = Long.parseLong(ids[i]);
                long b = Long.parseLong(ids[i + 1]);
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT ta.fqn, tb.fqn, e.kind, files.path, e.line
                        FROM edges e
                        LEFT JOIN symbols ms ON ms.id = e.src_symbol_id
                        LEFT JOIN symbols ta ON ta.id = COALESCE(ms.parent_id, e.src_symbol_id)
                        LEFT JOIN symbols md ON md.id = e.dst_symbol_id
                        LEFT JOIN symbols tb ON tb.id = COALESCE(md.parent_id, e.dst_symbol_id)
                        LEFT JOIN files ON files.id = e.file_id
                        WHERE ta.id = ? AND tb.id = ?
                          AND e.kind IN ('CALLS','STATIC_CALL','NEW','EXTENDS','IMPLEMENTS')
                        ORDER BY e.line LIMIT 1
                        """)) {
                    ps.setLong(1, a); ps.setLong(2, b);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) hops.add(new DependencyHop(
                                rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), (Integer) rs.getObject(5)));
                    }
                }
            }
        }
        return hops;
    }

    // ------------------------------------------------------ utility lookups

    public Optional<Long> resolveMethodId(String classFqn, String method) throws SQLException {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT m.id FROM symbols m JOIN symbols t ON m.parent_id=t.id " +
                     "WHERE m.kind IN ('METHOD','CONSTRUCTOR') AND m.simple_name=? " +
                     "  AND (t.fqn=? OR t.simple_name=?) LIMIT 1")) {
            ps.setString(1, method);
            ps.setString(2, classFqn);
            ps.setString(3, classFqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public Optional<Long> resolveTypeId(String classFqnOrSimple) throws SQLException {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM symbols WHERE kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION_TYPE') " +
                     "  AND (fqn=? OR simple_name=?) LIMIT 1")) {
            ps.setString(1, classFqnOrSimple);
            ps.setString(2, classFqnOrSimple);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Convert the linear FlowNode list into a tree-shaped Map for nicer LLM output. */
    public Map<String, Object> shapeFlowAsTree(List<FlowNode> nodes) {
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Object> root = null;
        for (FlowNode n : nodes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", n.classFqn());
            entry.put("method", n.method());
            entry.put("signature", n.signature());
            entry.put("role", n.role());
            entry.put("file", n.file());
            entry.put("line", n.line());
            entry.put("depth", n.depth());
            entry.put("children", new ArrayList<Map<String, Object>>());
            byId.put(n.symbolId(), entry);

            if (n.depth() == 0) { root = entry; continue; }
            // parent is the last id before this one in the dotted path
            String path = n.parentPath();
            String[] parts = path.split(">");
            if (parts.length >= 2) {
                long parentId = Long.parseLong(parts[parts.length - 2]);
                Map<String, Object> parent = byId.get(parentId);
                if (parent != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> kids = (List<Map<String, Object>>) parent.get("children");
                    kids.add(entry);
                }
            }
        }
        return root == null ? Map.of() : root;
    }
}
