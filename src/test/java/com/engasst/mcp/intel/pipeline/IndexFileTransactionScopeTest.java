package com.engasst.mcp.intel.pipeline;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.db.Database;
import com.engasst.mcp.intel.model.SymbolRow;
import com.engasst.mcp.intel.store.IndexStore;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-2 verification:
 * <ul>
 *   <li>Parse + extract must execute <em>before</em> {@link IndexStore#runInWriteTransaction}
 *       opens (decisive ordering test).</li>
 *   <li>The per-file transaction body must contain only the 7 P0-2 contract ops,
 *       and no contract op may execute outside a transaction.</li>
 *   <li>No symbol or edge may persist the sentinel {@code fileId = -1L} that
 *       {@link SymbolExtractor#extract} stamps before {@link IndexStore#upsertFile}
 *       returns the real id.</li>
 * </ul>
 */
class IndexFileTransactionScopeTest {

    /**
     * Records, for every store write call, whether it executed inside an
     * active {@code runInWriteTransaction} or not, plus a monotonic timestamp
     * at the moment {@code runInWriteTransaction} was first entered. Single
     * test thread only -- not safe for concurrent use.
     */
    static final class RecordingStore extends IndexStore {
        final AtomicBoolean inTxn = new AtomicBoolean(false);
        final AtomicLong firstTxnEntryNs = new AtomicLong(0L);
        final List<String> insideTxn  = new ArrayList<>();
        final List<String> outsideTxn = new ArrayList<>();
        int txnEntries = 0;

        RecordingStore(Database db) { super(db); }

        @Override
        public void runInWriteTransaction(WriteAction action) throws Exception {
            // System.nanoTime is monotonic on every supported JVM; we only
            // compare values captured on the same thread, so no clock skew.
            if (firstTxnEntryNs.get() == 0L) firstTxnEntryNs.set(System.nanoTime());
            txnEntries++;
            inTxn.set(true);
            try { super.runInWriteTransaction(action); }
            finally { inTxn.set(false); }
        }

        private void record(String name) {
            (inTxn.get() ? insideTxn : outsideTxn).add(name);
        }

        @Override public long upsertFile(long repoId, String path, long mtime, String sha, long size) throws java.sql.SQLException {
            record("upsertFile"); return super.upsertFile(repoId, path, mtime, sha, size);
        }
        @Override public void purgeFileContents(long fileId) throws java.sql.SQLException {
            record("purgeFileContents"); super.purgeFileContents(fileId);
        }
        @Override public void insertImport(long fileId, String fqn, boolean isStatic, boolean isWildcard) throws java.sql.SQLException {
            record("insertImport"); super.insertImport(fileId, fqn, isStatic, isWildcard);
        }
        @Override public void insertSymbols(Collection<SymbolRow> rows) throws java.sql.SQLException {
            record("insertSymbols"); super.insertSymbols(rows);
        }
        @Override public void insertAnnotation(long symbolId, String name, String fqn, String argsJson) throws java.sql.SQLException {
            record("insertAnnotation"); super.insertAnnotation(symbolId, name, fqn, argsJson);
        }
        @Override public void insertEdges(Collection<com.engasst.mcp.intel.model.EdgeRow> rows) throws java.sql.SQLException {
            record("insertEdges"); super.insertEdges(rows);
        }
    }

    /**
     * Spy on {@link ParsedFileCache} that records a monotonic timestamp at the
     * moment {@link #parse} is first entered for any file. Test then compares
     * that timestamp against the txn-entry timestamp recorded by
     * {@link RecordingStore}. If parse runs inside the transaction, the parse
     * timestamp will be greater than the txn-entry timestamp and the ordering
     * assertion fails.
     */
    static final class RecordingParsedFileCache extends ParsedFileCache {
        final AtomicLong firstParseNs = new AtomicLong(0L);
        int parseCalls = 0;

        @Override
        public Optional<CompilationUnit> parse(Path file) {
            if (firstParseNs.get() == 0L) firstParseNs.set(System.nanoTime());
            parseCalls++;
            return super.parse(file);
        }
    }

    private static Path writeSample(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/x"));
        Path file = src.resolve("Hello.java");
        Files.writeString(file, """
                package x;
                import java.util.List;
                public class Hello {
                    public String greet(List<String> who) { return "hi"; }
                }
                """);
        return file;
    }

    @Test
    void allPerFileWritesOccurInsideATransactionAndNoneOutside(@TempDir Path tmp) throws Exception {
        writeSample(tmp);
        ServerConfig cfg = ServerConfig.load(tmp);
        RepoWalker walker = new RepoWalker(cfg);
        ParsedFileCache cache = new ParsedFileCache();
        Database db = Database.open(tmp.resolve("intel.db"));
        try {
            RecordingStore store = new RecordingStore(db);
            IndexingPipeline pipeline = new IndexingPipeline(walker, cfg, store, cache);

            pipeline.build(tmp, false);

            // At least one txn must have been opened. We do not pin the exact
            // count -- a future correctness improvement that wraps additional
            // pipeline work (e.g. the linker call) in its own transaction
            // must not break this test.
            assertTrue(store.txnEntries >= 1,
                    "Pipeline must open at least one write transaction");

            // Every per-file write must be inside *some* transaction.
            assertTrue(store.insideTxn.contains("upsertFile"),         store.insideTxn::toString);
            assertTrue(store.insideTxn.contains("purgeFileContents"),  store.insideTxn::toString);
            assertTrue(store.insideTxn.contains("insertImport"),       store.insideTxn::toString);
            assertTrue(store.insideTxn.contains("insertSymbols"),      store.insideTxn::toString);
            assertTrue(store.insideTxn.contains("insertEdges"),        store.insideTxn::toString);

            // And no per-file write may have executed outside a transaction.
            assertTrue(store.outsideTxn.isEmpty(),
                    "No per-file contract op may execute outside a transaction; saw: "
                  + store.outsideTxn);
        } finally {
            db.close();
        }
    }

    /**
     * Decisive ordering proof (G-1). Captures monotonic nanoTime stamps at the
     * moment {@code cache.parse} is entered and at the moment
     * {@code runInWriteTransaction} is entered. Asserts the parse stamp comes
     * strictly before the txn-entry stamp. If a future refactor moves parsing
     * back inside the transaction, the order flips and this test fails.
     */
    @Test
    void parseEntersBeforeAnyTransactionEntry(@TempDir Path tmp) throws Exception {
        writeSample(tmp);
        ServerConfig cfg = ServerConfig.load(tmp);
        RepoWalker walker = new RepoWalker(cfg);
        RecordingParsedFileCache cache = new RecordingParsedFileCache();
        Database db = Database.open(tmp.resolve("intel.db"));
        try {
            RecordingStore store = new RecordingStore(db);
            IndexingPipeline pipeline = new IndexingPipeline(walker, cfg, store, cache);

            pipeline.build(tmp, false);

            long parseNs = cache.firstParseNs.get();
            long txnNs   = store.firstTxnEntryNs.get();

            assertTrue(parseNs > 0L,
                    "cache.parse must have been invoked at least once");
            assertTrue(txnNs > 0L,
                    "runInWriteTransaction must have been invoked at least once");
            assertTrue(parseNs < txnNs,
                    "parse must execute BEFORE the first write transaction opens; "
                  + "parseNs=" + parseNs + " txnNs=" + txnNs);
        } finally {
            db.close();
        }
    }

    /**
     * Sentinel-leak proof (G-2). After a full build, no symbol or edge row
     * may carry {@code file_id = -1}. If the patch loops in
     * {@code IndexingPipeline.indexFile} are removed or regress, the sentinel
     * stamped by {@code SymbolExtractor.extract(..., -1L)} would persist and
     * one of these counts would become non-zero.
     */
    @Test
    void noRowPersistsTheSentinelFileId(@TempDir Path tmp) throws Exception {
        writeSample(tmp);
        ServerConfig cfg = ServerConfig.load(tmp);
        RepoWalker walker = new RepoWalker(cfg);
        ParsedFileCache cache = new ParsedFileCache();
        Database db = Database.open(tmp.resolve("intel.db"));
        try {
            IndexStore store = new IndexStore(db);
            IndexingPipeline pipeline = new IndexingPipeline(walker, cfg, store, cache);

            pipeline.build(tmp, false);

            assertEquals(0, countSentinel(store, "symbols"),
                    "No symbols row may persist the extractor sentinel file_id=-1");
            assertEquals(0, countSentinel(store, "edges"),
                    "No edges row may persist the extractor sentinel file_id=-1");

            // And we must have actually written something -- otherwise the
            // count-equals-zero assertion would pass vacuously.
            assertTrue(rowCount(store, "symbols") > 0,
                    "Expected the fixture file to produce symbols");
        } finally {
            db.close();
        }
    }

    private static int countSentinel(IndexStore store, String table) throws Exception {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM " + table + " WHERE file_id = -1")) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int rowCount(IndexStore store, String table) throws Exception {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table)) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
