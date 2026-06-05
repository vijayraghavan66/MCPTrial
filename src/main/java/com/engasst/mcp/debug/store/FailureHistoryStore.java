package com.engasst.mcp.debug.store;

import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.store.IndexStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Persists every analysed failure so future investigations can score the
 * "historical failure correlation" factor without re-deriving it.
 */
public final class FailureHistoryStore {

    private final IndexStore store;

    public FailureHistoryStore(IndexStore store) { this.store = store; }

    /** Build the normalised signature used as the correlation key. */
    public static String signature(String exceptionFqn, String topClassFqn, String topMethod) {
        return (exceptionFqn == null ? "?" : exceptionFqn)
                + "|" + (topClassFqn == null ? "?" : topClassFqn)
                + "#" + (topMethod   == null ? "?" : topMethod);
    }

    public void record(Long repoId, String signature, String exceptionFqn,
                       String topClassFqn, String topMethod,
                       String filePath, String source) throws SQLException {
        Database db = store.db();
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "INSERT INTO failure_history(repo_id, signature, exception_fqn, top_class_fqn," +
                " top_method, file_path, source, occurred_at) VALUES(?,?,?,?,?,?,?,?)")) {
            if (repoId == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setLong(1, repoId);
            ps.setString(2, signature);
            ps.setString(3, exceptionFqn);
            ps.setString(4, topClassFqn);
            ps.setString(5, topMethod);
            ps.setString(6, filePath);
            ps.setString(7, source);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    /** Count prior occurrences of this exact signature. */
    public int priorOccurrences(String signature) throws SQLException {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM failure_history WHERE signature=?")) {
            ps.setString(1, signature);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /** Count prior occurrences of the same exception class anywhere. */
    public int priorByException(String exceptionFqn) throws SQLException {
        if (exceptionFqn == null) return 0;
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM failure_history WHERE exception_fqn=?")) {
            ps.setString(1, exceptionFqn);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
