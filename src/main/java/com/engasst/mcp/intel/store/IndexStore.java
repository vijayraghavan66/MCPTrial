package com.engasst.mcp.intel.store;

import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.model.EdgeRow;
import com.engasst.mcp.intel.model.IndexStatus;
import com.engasst.mcp.intel.model.SymbolRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single point of SQL for the intelligence subsystem. Writes funnel through
 * {@link Database#writeConnection()} guarded by {@link Database#writeLock()};
 * reads use fresh read connections.
 */
public class IndexStore {

    private final Database db;
    /**
     * Per-thread flag that records whether a {@link #runInWriteTransaction}
     * call is currently in progress on this thread. When set, the individual
     * write methods skip their own {@code autoCommit} toggling and
     * {@code commit}/{@code rollback} so the outer call owns the boundary.
     */
    private final ThreadLocal<Boolean> inTxn = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public IndexStore(Database db) { this.db = db; }

    /** Action executed under {@link #runInWriteTransaction}; may throw checked or unchecked. */
    @FunctionalInterface
    public interface WriteAction {
        void run() throws Exception;
    }

    /**
     * Run {@code action} under the write lock with a single SQLite transaction.
     * All writes performed by this thread inside {@code action} (whether direct
     * SQL on the write connection or calls to other {@link IndexStore} methods)
     * are atomic: either every change commits, or every change rolls back.
     *
     * <p>Reentrant: a nested call just runs the action inline, leaving the
     * outermost call as the single commit point.
     *
     * <p><b>Contract for callers:</b>
     * <ul>
     *   <li>The action MUST NOT open new database connections (via
     *       {@link Database#readConnection()} or otherwise). A fresh read
     *       connection sees a WAL snapshot taken at its own {@code BEGIN} and
     *       therefore does <em>not</em> observe the in-flight writes; opening a
     *       second write connection would block on the SQLite write lock.</li>
     *   <li>Only do CPU/IO work that is cheap and bounded inside the action.
     *       Heavy parsing or file IO should happen <em>before</em> the action
     *       is invoked, so the SQLite write lock and the JVM {@code writeLock}
     *       are held only for the duration of the SQL writes.</li>
     *   <li>Any {@link IndexStore} write method called from inside the action
     *       participates in the outer transaction. Methods that toggle
     *       {@code autoCommit} themselves (currently {@link #insertSymbols} and
     *       {@link #insertEdges}) detect the outer transaction via {@link #inTxn}
     *       and skip their own commit/rollback. All other write methods are
     *       transaction-agnostic: they issue plain statements on the shared
     *       write connection and therefore inherit whatever transaction state
     *       the caller has established. They MUST NOT call {@code commit},
     *       {@code rollback}, {@code setAutoCommit}, or create savepoints.</li>
     * </ul>
     */
    public void runInWriteTransaction(WriteAction action) throws Exception {
        if (Boolean.TRUE.equals(inTxn.get())) {
            action.run();
            return;
        }
        db.writeLock().lock();
        Connection c = db.writeConnection();
        boolean prevAuto = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            inTxn.set(Boolean.TRUE);
            try {
                action.run();
                c.commit();
            } catch (Throwable t) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw t;
            }
        } finally {
            inTxn.set(Boolean.FALSE);
            try { c.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            db.writeLock().unlock();
        }
    }

    // ----------------------------------------------------------------- repos

    public long upsertRepo(String rootPath) throws SQLException {
        db.writeLock().lock();
        try {
            Connection c = db.writeConnection();
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id FROM repos WHERE root_path = ?")) {
                sel.setString(1, rootPath);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO repos(root_path, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, rootPath);
                ins.setLong(2, System.currentTimeMillis());
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    try (PreparedStatement st = c.prepareStatement(
                            "INSERT OR REPLACE INTO index_status(repo_id, state, phase) VALUES(?, 'IDLE', 'IDLE')")) {
                        st.setLong(1, id);
                        st.executeUpdate();
                    }
                    return id;
                }
            }
        } finally { db.writeLock().unlock(); }
    }

    public void markRepoIndexed(long repoId) throws SQLException {
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "UPDATE repos SET last_indexed_at=? WHERE id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, repoId);
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    // ----------------------------------------------------------------- files

    public Map<String, FileFingerprint> loadFileFingerprints(long repoId) throws SQLException {
        Map<String, FileFingerprint> out = new HashMap<>();
        try (Connection c = db.readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, path, mtime, sha FROM files WHERE repo_id=?")) {
            ps.setLong(1, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(2),
                            new FileFingerprint(rs.getLong(1), rs.getLong(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    public long upsertFile(long repoId, String path, long mtime, String sha, long size) throws SQLException {
        db.writeLock().lock();
        try {
            Connection c = db.writeConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO files(repo_id, path, mtime, sha, size, indexed_at) " +
                    "VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(repo_id, path) DO UPDATE SET " +
                    "  mtime=excluded.mtime, sha=excluded.sha, size=excluded.size, indexed_at=excluded.indexed_at",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, repoId);
                ps.setString(2, path);
                ps.setLong(3, mtime);
                ps.setString(4, sha);
                ps.setLong(5, size);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT id FROM files WHERE repo_id=? AND path=?")) {
                sel.setLong(1, repoId);
                sel.setString(2, path);
                try (ResultSet rs = sel.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        } finally { db.writeLock().unlock(); }
    }

    public void deleteFile(long fileId) throws SQLException {
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "DELETE FROM files WHERE id=?")) {
            ps.setLong(1, fileId);
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    public void purgeFileContents(long fileId) throws SQLException {
        db.writeLock().lock();
        Connection c = db.writeConnection();
        try {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM symbols WHERE file_id=?")) {
                ps.setLong(1, fileId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM edges   WHERE file_id=?")) {
                ps.setLong(1, fileId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM imports WHERE file_id=?")) {
                ps.setLong(1, fileId); ps.executeUpdate();
            }
        } finally { db.writeLock().unlock(); }
    }

    // --------------------------------------------------------------- symbols

    /** Insert a batch of symbols and back-fill {@link SymbolRow#id} on each. */
    public void insertSymbols(Collection<SymbolRow> rows) throws SQLException {
        if (rows.isEmpty()) return;
        db.writeLock().lock();
        Connection c = db.writeConnection();
        boolean ownTxn = !Boolean.TRUE.equals(inTxn.get());
        boolean prevAuto = c.getAutoCommit();
        if (ownTxn) c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO symbols(repo_id,file_id,parent_id,kind,package,simple_name,fqn," +
                "signature,return_type,modifiers,framework_role,start_line,end_line,param_types) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            for (SymbolRow r : rows) {
                if (r.parentRef != null && r.parentId == null) r.parentId = r.parentRef.id;
                ps.setLong(1, r.repoId);
                setLongOrNull(ps, 2, r.fileId);
                setLongOrNull(ps, 3, r.parentId);
                ps.setString(4, r.kind.name());
                ps.setString(5, r.pkg);
                ps.setString(6, r.simpleName);
                ps.setString(7, r.fqn);
                ps.setString(8, r.signature);
                ps.setString(9, r.returnType);
                ps.setString(10, r.modifiers);
                ps.setString(11, r.frameworkRole);
                setIntOrNull(ps, 12, r.startLine);
                setIntOrNull(ps, 13, r.endLine);
                ps.setString(14, r.paramTypes);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) { keys.next(); r.id = keys.getLong(1); }
            }
            if (ownTxn) c.commit();
        } catch (SQLException ex) {
            if (ownTxn) c.rollback();
            throw ex;
        } finally {
            if (ownTxn) c.setAutoCommit(prevAuto);
            db.writeLock().unlock();
        }
    }

    public void insertAnnotation(long symbolId, String name, String fqn, String argsJson) throws SQLException {
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "INSERT INTO annotations(symbol_id, name, fqn, args_json) VALUES(?,?,?,?)")) {
            ps.setLong(1, symbolId);
            ps.setString(2, name);
            ps.setString(3, fqn);
            ps.setString(4, argsJson);
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    public void insertImport(long fileId, String fqn, boolean isStatic, boolean isWildcard) throws SQLException {
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "INSERT INTO imports(file_id, fqn, is_static, is_wildcard) VALUES(?,?,?,?)")) {
            ps.setLong(1, fileId);
            ps.setString(2, fqn);
            ps.setInt(3, isStatic ? 1 : 0);
            ps.setInt(4, isWildcard ? 1 : 0);
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    // ----------------------------------------------------------------- edges

    public void insertEdges(Collection<EdgeRow> rows) throws SQLException {
        if (rows.isEmpty()) return;
        db.writeLock().lock();
        Connection c = db.writeConnection();
        boolean ownTxn = !Boolean.TRUE.equals(inTxn.get());
        boolean prevAuto = c.getAutoCommit();
        if (ownTxn) c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO edges(repo_id,src_symbol_id,dst_symbol_id,dst_fqn,dst_member,kind,file_id,line,resolution,param_types) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (EdgeRow e : rows) {
                ps.setLong(1, e.repoId);
                ps.setLong(2, e.srcSymbolId);
                setLongOrNull(ps, 3, e.dstSymbolId);
                ps.setString(4, e.dstFqn);
                ps.setString(5, e.dstMember);
                ps.setString(6, e.kind.name());
                setLongOrNull(ps, 7, e.fileId);
                setIntOrNull(ps, 8, e.line);
                ps.setString(9, e.resolution);
                ps.setString(10, e.paramTypes);
                ps.addBatch();
            }
            ps.executeBatch();
            if (ownTxn) c.commit();
        } catch (SQLException ex) {
            if (ownTxn) c.rollback();
            throw ex;
        } finally {
            if (ownTxn) c.setAutoCommit(prevAuto);
            db.writeLock().unlock();
        }
    }

    /**
     * Linker pass: resolve dangling edges to known symbols and mark resolution=ast.
     *
     * <p>Per-kind strategy:
     * <ul>
     *   <li>{@code NEW} -> CONSTRUCTOR only.
     *       Pass A matches on {@code (parent.fqn, '<init>', param_types)} when both
     *       sides have a non-null param signature -> picks the exact overload.
     *       Pass B is a name-only fallback that fires <em>only</em> when the target
     *       class has exactly one constructor (so single-ctor classes resolve even
     *       when the symbol solver failed to recover the call's arg types).</li>
     *   <li>{@code CALLS / STATIC_CALL} -> METHOD only, name match (unchanged).</li>
     *   <li>{@code FIELD_READ / FIELD_WRITE} -> FIELD (unchanged).</li>
     *   <li>{@code EXTENDS / IMPLEMENTS / ANNOTATED_WITH / THROWS} -> type (unchanged).</li>
     * </ul>
     */
    public int linkUnresolvedEdges(long repoId) throws SQLException {
        // All five linker passes must commit atomically -- otherwise a reader
        // whose WAL snapshot lands between two passes would observe a database
        // where, say, constructor edges are linked but method edges are not.
        db.writeLock().lock();
        Connection c = db.writeConnection();
        boolean ownTxn = !Boolean.TRUE.equals(inTxn.get());
        boolean prevAuto = c.getAutoCommit();
        if (ownTxn) {
            c.setAutoCommit(false);
            inTxn.set(Boolean.TRUE);
        }
        try (Statement s = c.createStatement()) {
            // --- NEW pass A: overload-aware (both sides have a known param signature) ---
            int linkedCtorsExact = s.executeUpdate(
                "UPDATE edges SET dst_symbol_id = (" +
                "  SELECT m.id FROM symbols m" +
                "  JOIN symbols t ON m.parent_id = t.id" +
                "  WHERE t.fqn = edges.dst_fqn" +
                "    AND m.kind = 'CONSTRUCTOR'" +
                "    AND m.param_types IS NOT NULL" +
                "    AND m.param_types = edges.param_types" +
                "  LIMIT 1" +
                "), resolution='ast' " +
                "WHERE edges.repo_id=" + repoId +
                "  AND edges.dst_symbol_id IS NULL" +
                "  AND edges.kind = 'NEW'" +
                "  AND edges.dst_fqn IS NOT NULL" +
                "  AND edges.param_types IS NOT NULL");

            // --- NEW pass B: fallback only for classes with a single ctor ---
            int linkedCtorsUnique = s.executeUpdate(
                "UPDATE edges SET dst_symbol_id = (" +
                "  SELECT m.id FROM symbols m" +
                "  JOIN symbols t ON m.parent_id = t.id" +
                "  WHERE t.fqn = edges.dst_fqn" +
                "    AND m.kind = 'CONSTRUCTOR'" +
                "    AND (SELECT COUNT(*) FROM symbols m2 JOIN symbols t2 ON m2.parent_id = t2.id" +
                "         WHERE t2.fqn = edges.dst_fqn AND m2.kind = 'CONSTRUCTOR') = 1" +
                "  LIMIT 1" +
                "), resolution='ast' " +
                "WHERE edges.repo_id=" + repoId +
                "  AND edges.dst_symbol_id IS NULL" +
                "  AND edges.kind = 'NEW'" +
                "  AND edges.dst_fqn IS NOT NULL");

            // --- Methods (CALLS / STATIC_CALL): name match against METHOD only ---
            int linkedMethods = s.executeUpdate(
                "UPDATE edges SET dst_symbol_id = (" +
                "  SELECT m.id FROM symbols m" +
                "  JOIN symbols t ON m.parent_id = t.id" +
                "  WHERE t.fqn = edges.dst_fqn" +
                "    AND m.simple_name = edges.dst_member" +
                "    AND m.kind = 'METHOD'" +
                "  LIMIT 1" +
                "), resolution='ast' " +
                "WHERE edges.repo_id=" + repoId +
                "  AND edges.dst_symbol_id IS NULL" +
                "  AND edges.kind IN ('CALLS','STATIC_CALL')" +
                "  AND edges.dst_fqn IS NOT NULL AND edges.dst_member IS NOT NULL");

            // Fields
            int linkedFields = s.executeUpdate(
                "UPDATE edges SET dst_symbol_id = (" +
                "  SELECT f.id FROM symbols f" +
                "  JOIN symbols t ON f.parent_id = t.id" +
                "  WHERE t.fqn = edges.dst_fqn" +
                "    AND f.simple_name = edges.dst_member" +
                "    AND f.kind = 'FIELD'" +
                "  LIMIT 1" +
                "), resolution='ast' " +
                "WHERE edges.repo_id=" + repoId +
                "  AND edges.dst_symbol_id IS NULL" +
                "  AND edges.kind IN ('FIELD_READ','FIELD_WRITE')");

            // Types (EXTENDS/IMPLEMENTS/ANNOTATED_WITH/THROWS)
            int linkedTypes = s.executeUpdate(
                "UPDATE edges SET dst_symbol_id = (" +
                "  SELECT t.id FROM symbols t WHERE t.fqn = edges.dst_fqn" +
                "  AND t.kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION_TYPE') LIMIT 1" +
                "), resolution='ast' " +
                "WHERE edges.repo_id=" + repoId +
                "  AND edges.dst_symbol_id IS NULL" +
                "  AND edges.kind IN ('EXTENDS','IMPLEMENTS','ANNOTATED_WITH','THROWS')");

            int total = linkedCtorsExact + linkedCtorsUnique + linkedMethods + linkedFields + linkedTypes;
            if (ownTxn) c.commit();
            return total;
        } catch (SQLException ex) {
            if (ownTxn) { try { c.rollback(); } catch (SQLException ignored) {} }
            throw ex;
        } finally {
            if (ownTxn) {
                inTxn.set(Boolean.FALSE);
                try { c.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
            db.writeLock().unlock();
        }
    }

    // --------------------------------------------------------------- status

    public void updateStatus(long repoId, String state, String phase, Integer total, Integer changed,
                             Integer done, Long started, Long finished, String error) throws SQLException {
        db.writeLock().lock();
        try (PreparedStatement ps = db.writeConnection().prepareStatement(
                "INSERT INTO index_status(repo_id, state, phase, files_total, files_changed, files_done, started_at, finished_at, error) " +
                "VALUES(?,COALESCE(?, 'IDLE'),?,?,?,?,?,?,?) " +
                "ON CONFLICT(repo_id) DO UPDATE SET " +
                " state=COALESCE(excluded.state, index_status.state)," +
                " phase=COALESCE(excluded.phase, index_status.phase)," +
                " files_total=COALESCE(excluded.files_total, index_status.files_total)," +
                " files_changed=COALESCE(excluded.files_changed, index_status.files_changed)," +
                " files_done=COALESCE(excluded.files_done, index_status.files_done)," +
                " started_at=COALESCE(excluded.started_at, index_status.started_at)," +
                " finished_at=COALESCE(excluded.finished_at, index_status.finished_at)," +
                " error=excluded.error")) {
            ps.setLong(1, repoId);
            ps.setString(2, state);
            ps.setString(3, phase);
            setIntOrNull(ps, 4, total);
            setIntOrNull(ps, 5, changed);
            setIntOrNull(ps, 6, done);
            setLongOrNull(ps, 7, started);
            setLongOrNull(ps, 8, finished);
            ps.setString(9, error);
            ps.executeUpdate();
        } finally { db.writeLock().unlock(); }
    }

    public void refreshCounters(long repoId) throws SQLException {
        db.writeLock().lock();
        Connection c = db.writeConnection();
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "UPDATE index_status SET" +
                " symbols_count = (SELECT COUNT(*) FROM symbols WHERE repo_id=" + repoId + ")," +
                " edges_count   = (SELECT COUNT(*) FROM edges   WHERE repo_id=" + repoId + ")" +
                " WHERE repo_id=" + repoId);
        } finally { db.writeLock().unlock(); }
    }

    public Optional<IndexStatus> status(long repoId) throws SQLException {
        try (Connection c = db.readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT r.id, r.root_path, s.state, s.phase, s.files_total, s.files_changed, s.files_done," +
                     " s.symbols_count, s.edges_count, s.started_at, s.finished_at, s.error " +
                     "FROM repos r LEFT JOIN index_status s ON s.repo_id=r.id WHERE r.id=?")) {
            ps.setLong(1, repoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new IndexStatus(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getLong(8), rs.getLong(9),
                        (Long) rs.getObject(10), (Long) rs.getObject(11),
                        rs.getString(12)));
            }
        }
    }

    public Optional<Long> repoIdByPath(String rootPath) throws SQLException {
        try (Connection c = db.readConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM repos WHERE root_path=?")) {
            ps.setString(1, rootPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------- accessors

    public Database db() { return db; }

    // ------------------------------------------------------------ utilities

    private static void setLongOrNull(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setLong(i, v);
    }
    private static void setIntOrNull(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    public record FileFingerprint(long fileId, long mtime, String sha) {}
}
