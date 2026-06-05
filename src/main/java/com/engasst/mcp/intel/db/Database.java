package com.engasst.mcp.intel.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the SQLite database backing the Code Intelligence Engine.
 *
 * <p>SQLite is used in WAL mode so readers never block writers. We expose
 * two connection categories:
 * <ul>
 *   <li>{@link #writeConnection()} — a single, long-lived connection. All
 *   writes funnel through it and are serialised by {@link #writeLock()}.</li>
 *   <li>{@link #readConnection()} — a fresh read connection per call. Cheap
 *   under WAL; callers are expected to close it.</li>
 * </ul>
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final Path dbFile;
    private final String url;
    private final Connection writeConn;
    private final ReentrantLock writeLock = new ReentrantLock();

    private Database(Path dbFile) throws SQLException {
        this.dbFile = dbFile;
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString().replace('\\', '/');
        this.writeConn = newRawConnection();
        applyPragmas(this.writeConn);
    }

    public static Database open(Path dbFile) throws IOException, SQLException {
        Files.createDirectories(dbFile.getParent());
        Database db = new Database(dbFile);
        db.applySchema();
        return db;
    }

    public Path file()              { return dbFile; }
    public Connection writeConnection() { return writeConn; }
    public ReentrantLock writeLock()    { return writeLock; }

    public Connection readConnection() throws SQLException {
        Connection c = newRawConnection();
        applyPragmas(c);
        return c;
    }

    private Connection newRawConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private static void applyPragmas(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA cache_size=-65536"); // 64 MB
            s.execute("PRAGMA mmap_size=268435456"); // 256 MB
        }
    }

    private void applySchema() throws SQLException {
        String ddl = loadResource("/intel-schema.sql");
        writeLock.lock();
        try (Statement s = writeConn.createStatement()) {
            StringBuilder cleaned = new StringBuilder();
            for (String line : ddl.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--")) continue;
                int comment = line.indexOf("--");
                if (comment >= 0) line = line.substring(0, comment);
                if (!line.isBlank()) cleaned.append(line).append('\n');
            }
            for (String stmt : cleaned.toString().split(";\\s*(?:\\r?\\n|$)")) {
                String t = stmt.trim();
                if (t.isEmpty()) continue;
                s.execute(t);
            }
            runMigrations(s);
        } finally {
            writeLock.unlock();
        }
        LOG.info("Code Intelligence DB ready at {}", dbFile);
    }

    /**
     * Idempotent migrations for databases created by earlier schema versions.
     * SQLite has no IF NOT EXISTS form for ALTER TABLE ADD COLUMN, so we
     * probe table_info() and emit the ALTER only when missing.
     *
     * v2 -> v3: add {@code param_types} to symbols and edges + supporting
     * indexes for constructor overload disambiguation.
     */
    private void runMigrations(Statement s) throws SQLException {
        if (!columnExists(s, "symbols", "param_types")) {
            s.execute("ALTER TABLE symbols ADD COLUMN param_types TEXT");
        }
        if (!columnExists(s, "edges", "param_types")) {
            s.execute("ALTER TABLE edges ADD COLUMN param_types TEXT");
        }
        // P0-3 build-state column. NOT NULL with a DEFAULT so existing rows
        // back-fill to 'IDLE' atomically with the ALTER.
        if (!columnExists(s, "index_status", "state")) {
            s.execute("ALTER TABLE index_status ADD COLUMN state TEXT NOT NULL DEFAULT 'IDLE'");
        }
        s.execute("CREATE INDEX IF NOT EXISTS idx_symbols_ctor_lookup " +
                  "ON symbols(parent_id, kind, simple_name, param_types)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_edges_dst_ctor " +
                  "ON edges(dst_fqn, kind, param_types)");
        s.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', '3')");
    }

    private static boolean columnExists(Statement s, String table, String column) throws SQLException {
        try (var rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private static String loadResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load " + path, ex);
        }
    }

    @Override
    public void close() {
        try { writeConn.close(); } catch (SQLException ignored) {}
    }
}
