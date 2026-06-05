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
import java.util.Optional;

/**
 * Produces a compact, LLM-friendly description of a single method: signature,
 * annotations, callers, callees, fields touched, throws.
 */
public final class ExplainService {

    private final IndexStore store;
    private final TraceService trace;

    public ExplainService(IndexStore store, TraceService trace) {
        this.store = store;
        this.trace = trace;
    }

    public Optional<Map<String, Object>> explain(String classFqnOrSimple, String method) throws SQLException {
        Optional<Long> idOpt = trace.resolveMethodId(classFqnOrSimple, method);
        if (idOpt.isEmpty()) return Optional.empty();
        return explainById(idOpt.get());
    }

    public Optional<Map<String, Object>> explainById(long methodId) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection c = store.db().readConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT m.simple_name, m.signature, m.return_type, m.modifiers, m.framework_role," +
                    " m.start_line, m.end_line, t.fqn, files.path " +
                    "FROM symbols m LEFT JOIN symbols t ON t.id=m.parent_id " +
                    "LEFT JOIN files ON files.id=m.file_id WHERE m.id=?")) {
                ps.setLong(1, methodId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    out.put("methodId", methodId);
                    out.put("class", rs.getString(8));
                    out.put("name", rs.getString(1));
                    out.put("signature", rs.getString(2));
                    out.put("returnType", rs.getString(3));
                    out.put("modifiers", rs.getString(4));
                    out.put("role", rs.getString(5));
                    out.put("file", rs.getString(9));
                    out.put("startLine", rs.getObject(6));
                    out.put("endLine", rs.getObject(7));
                }
            }

            out.put("annotations", listAnnotations(c, methodId));
            out.put("throws",      listOutEdges(c, methodId, "THROWS"));
            out.put("calls",       listOutEdges(c, methodId, "CALLS", "STATIC_CALL", "NEW"));
            out.put("fieldReads",  listOutEdges(c, methodId, "FIELD_READ"));
            out.put("fieldWrites", listOutEdges(c, methodId, "FIELD_WRITE"));
            out.put("callers",     listInEdges(c, methodId));
        }
        return Optional.of(out);
    }

    private List<Map<String, Object>> listAnnotations(Connection c, long symbolId) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, fqn FROM annotations WHERE symbol_id=?")) {
            ps.setLong(1, symbolId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("name", rs.getString(1));
                    a.put("fqn", rs.getString(2));
                    out.add(a);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> listOutEdges(Connection c, long src, String... kinds) throws SQLException {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) in.append(i == 0 ? "?" : ",?");
        String sql = "SELECT e.kind, e.dst_fqn, e.dst_member, e.line, e.resolution " +
                     "FROM edges e WHERE e.src_symbol_id=? AND e.kind IN (" + in + ") ORDER BY e.line";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, src);
            for (String k : kinds) ps.setString(i++, k);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString(1));
                    m.put("targetClass", rs.getString(2));
                    m.put("targetMember", rs.getString(3));
                    m.put("line", rs.getObject(4));
                    m.put("resolution", rs.getString(5));
                    out.add(m);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> listInEdges(Connection c, long dst) throws SQLException {
        String sql =
                "SELECT t.fqn, m.simple_name, files.path, e.line " +
                "FROM edges e " +
                "JOIN symbols m ON m.id=e.src_symbol_id " +
                "LEFT JOIN symbols t ON t.id=m.parent_id " +
                "LEFT JOIN files ON files.id=e.file_id " +
                "WHERE e.dst_symbol_id=? AND e.kind IN ('CALLS','STATIC_CALL','NEW') " +
                "ORDER BY t.fqn, m.simple_name";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, dst);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("callerClass", rs.getString(1));
                    m.put("callerMethod", rs.getString(2));
                    m.put("file", rs.getString(3));
                    m.put("line", rs.getObject(4));
                    out.add(m);
                }
            }
        }
        return out;
    }
}
