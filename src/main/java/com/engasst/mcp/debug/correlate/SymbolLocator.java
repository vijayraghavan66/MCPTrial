package com.engasst.mcp.debug.correlate;

import com.engasst.mcp.debug.model.StackFrame;
import com.engasst.mcp.intel.store.IndexStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a parsed {@link StackFrame} to a concrete symbol in the persistent
 * index (when one exists). Annotates the frame in place — no allocation.
 */
public final class SymbolLocator {

    private final IndexStore store;

    public SymbolLocator(IndexStore store) { this.store = store; }

    /** Returns true if any frame matched a repo symbol. */
    public boolean locate(Iterable<StackFrame> frames) throws SQLException {
        boolean any = false;
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement("""
                 SELECT m.id, files.path
                 FROM symbols m
                 JOIN symbols t ON m.parent_id = t.id
                 LEFT JOIN files ON files.id = m.file_id
                 WHERE m.kind IN ('METHOD','CONSTRUCTOR')
                   AND m.simple_name = ?
                   AND t.fqn = ?
                 LIMIT 1
                 """)) {
            for (StackFrame f : frames) {
                ps.setString(1, normaliseMethod(f.method));
                ps.setString(2, f.classFqn);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        f.symbolId = rs.getLong(1);
                        f.filePath = rs.getString(2);
                        f.inRepo = true;
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    /** Resolve a single (class, method) pair to a symbol id. */
    public Long resolve(String classFqn, String method) throws SQLException {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT m.id FROM symbols m JOIN symbols t ON m.parent_id=t.id " +
                 "WHERE m.kind IN ('METHOD','CONSTRUCTOR') AND m.simple_name=? " +
                 "  AND (t.fqn=? OR t.simple_name=?) LIMIT 1")) {
            ps.setString(1, normaliseMethod(method));
            ps.setString(2, classFqn);
            ps.setString(3, classFqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Returns the repo-relative file path for a given class FQN, or null. */
    public String fileForClass(String classFqn) throws SQLException {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT files.path FROM symbols t LEFT JOIN files ON files.id=t.file_id " +
                 "WHERE t.kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION_TYPE') " +
                 "  AND (t.fqn=? OR t.simple_name=?) LIMIT 1")) {
            ps.setString(1, classFqn);
            ps.setString(2, classFqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String normaliseMethod(String m) {
        if (m == null) return null;
        if ("<init>".equals(m) || "<clinit>".equals(m)) return m;
        // Lambdas show up as "lambda$foo$0" — strip to "foo".
        if (m.startsWith("lambda$")) {
            int end = m.indexOf('$', 7);
            return end > 7 ? m.substring(7, end) : m.substring(7);
        }
        return m;
    }
}
