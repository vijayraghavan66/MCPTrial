package com.engasst.mcp.intel.store;

import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.model.EdgeKind;
import com.engasst.mcp.intel.model.EdgeRow;
import com.engasst.mcp.intel.model.SymbolKind;
import com.engasst.mcp.intel.model.SymbolRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-2: per-file indexing must be atomic.
 *
 * <p>Covers {@link IndexStore#runInWriteTransaction}: success commits, any
 * exception (checked, unchecked, or {@link Error}) rolls every nested write
 * back, and a successful nested-after-failure call still commits cleanly.
 */
class WriteTransactionTest {

    private static IndexStore openStore(Path tmp) throws Exception {
        Database db = Database.open(tmp.resolve("intel.db"));
        return new IndexStore(db);
    }

    private static long repo(IndexStore store, Path tmp) throws Exception {
        return store.upsertRepo(tmp.toAbsolutePath().normalize().toString());
    }

    private static long file(IndexStore store, long repoId, String path) throws Exception {
        return store.upsertFile(repoId, path, 1L, "sha-" + path, 1L);
    }

    private static SymbolRow classRow(long repoId, long fileId, String fqn) {
        SymbolRow s = new SymbolRow();
        s.repoId = repoId; s.fileId = fileId; s.kind = SymbolKind.CLASS;
        s.pkg = "x"; s.simpleName = fqn.substring(fqn.lastIndexOf('.') + 1); s.fqn = fqn;
        return s;
    }

    private static EdgeRow extendsEdge(long repoId, long fileId, SymbolRow src, String dstFqn) {
        EdgeRow e = new EdgeRow();
        e.repoId = repoId; e.fileId = fileId; e.kind = EdgeKind.EXTENDS;
        e.srcRef = src; e.dstFqn = dstFqn; e.resolution = "unresolved";
        return e;
    }

    private static int count(IndexStore store, String table, long fileId) throws Exception {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE file_id=?")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    @Test
    void successfulTransactionCommitsEveryWrite(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = file(store, repoId, "Good.java");

            store.runInWriteTransaction(() -> {
                store.purgeFileContents(fileId);
                SymbolRow s = classRow(repoId, fileId, "x.Good");
                store.insertSymbols(List.of(s));
                store.insertImport(fileId, "java.util.List", false, false);
                EdgeRow e = extendsEdge(repoId, fileId, s, "x.Base");
                e.srcSymbolId = s.id;
                store.insertEdges(List.of(e));
            });

            assertEquals(1, count(store, "symbols", fileId));
            assertEquals(1, count(store, "edges",   fileId));
            assertEquals(1, count(store, "imports", fileId));
        } finally {
            store.db().close();
        }
    }

    @Test
    void exceptionMidwayRollsBackEveryWrite(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = file(store, repoId, "Boom.java");

            // First seed one row that we'll later assert is gone.
            RuntimeException boom = assertThrows(RuntimeException.class, () ->
                store.runInWriteTransaction(() -> {
                    store.purgeFileContents(fileId);
                    SymbolRow s = classRow(repoId, fileId, "x.Boom");
                    store.insertSymbols(List.of(s));
                    store.insertImport(fileId, "java.util.List", false, false);
                    throw new RuntimeException("simulated extractor failure");
                }));
            assertEquals("simulated extractor failure", boom.getMessage());

            assertEquals(0, count(store, "symbols", fileId),
                    "symbols inserted before the exception must be rolled back");
            assertEquals(0, count(store, "imports", fileId),
                    "imports inserted before the exception must be rolled back");
            assertEquals(0, count(store, "edges",   fileId));
        } finally {
            store.db().close();
        }
    }

    @Test
    void purgeRollsBackWhenLaterStepFails(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = file(store, repoId, "Existing.java");

            // Commit an existing baseline outside the failing txn.
            store.runInWriteTransaction(() -> {
                SymbolRow s = classRow(repoId, fileId, "x.Existing");
                store.insertSymbols(List.of(s));
                store.insertImport(fileId, "java.util.Map", false, false);
            });
            assertEquals(1, count(store, "symbols", fileId));
            assertEquals(1, count(store, "imports", fileId));

            // Now simulate a reindex that purges then crashes. The purge must be
            // undone -- the baseline rows must still be visible to readers.
            assertThrows(IllegalStateException.class, () ->
                store.runInWriteTransaction(() -> {
                    store.purgeFileContents(fileId);
                    throw new IllegalStateException("crash after purge");
                }));

            assertEquals(1, count(store, "symbols", fileId),
                    "Pre-existing row must survive a rolled-back reindex of the same file");
            assertEquals(1, count(store, "imports", fileId));
        } finally {
            store.db().close();
        }
    }

    @Test
    void reentrantTransactionIsHandledInline(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = file(store, repoId, "Nested.java");

            store.runInWriteTransaction(() -> {
                SymbolRow outer = classRow(repoId, fileId, "x.Outer");
                store.insertSymbols(List.of(outer));
                // nested call must NOT commit on its own
                store.runInWriteTransaction(() -> {
                    SymbolRow inner = classRow(repoId, fileId, "x.Inner");
                    store.insertSymbols(List.of(inner));
                });
            });
            assertEquals(2, count(store, "symbols", fileId));
        } finally {
            store.db().close();
        }
    }

    @Test
    void reentrantTransactionPropagatesRollback(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = file(store, repoId, "NestedBoom.java");

            assertThrows(RuntimeException.class, () ->
                store.runInWriteTransaction(() -> {
                    SymbolRow outer = classRow(repoId, fileId, "x.NOuter");
                    store.insertSymbols(List.of(outer));
                    store.runInWriteTransaction(() -> {
                        SymbolRow inner = classRow(repoId, fileId, "x.NInner");
                        store.insertSymbols(List.of(inner));
                        throw new RuntimeException("nested failure");
                    });
                }));

            assertEquals(0, count(store, "symbols", fileId),
                    "Failure from a nested action must roll the outer transaction back too");
        } finally {
            store.db().close();
        }
    }

    @Test
    void autoCommitIsRestoredAfterTransaction(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);

            // After a successful txn, plain writes (which assume autoCommit=true)
            // must still take effect immediately.
            store.runInWriteTransaction(() -> {});
            long fileId = file(store, repoId, "AfterTxn.java");
            assertTrue(fileId > 0);

            // And after a failed txn too.
            assertThrows(RuntimeException.class, () ->
                store.runInWriteTransaction(() -> { throw new RuntimeException("x"); }));
            long anotherId = file(store, repoId, "AfterFailedTxn.java");
            assertTrue(anotherId > 0);
            assertNotEquals(fileId, anotherId);
        } finally {
            store.db().close();
        }
    }
}
