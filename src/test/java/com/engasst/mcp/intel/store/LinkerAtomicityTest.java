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
 * P0-2 follow-up: {@link IndexStore#linkUnresolvedEdges} must commit all five
 * linker passes atomically. A reader must never observe partially linked state.
 */
class LinkerAtomicityTest {

    private static IndexStore openStore(Path tmp) throws Exception {
        Database db = Database.open(tmp.resolve("intel.db"));
        return new IndexStore(db);
    }

    private static long repo(IndexStore store, Path tmp) throws Exception {
        return store.upsertRepo(tmp.toAbsolutePath().normalize().toString());
    }

    private static SymbolRow type(long repoId, long fileId, SymbolKind kind, String fqn) {
        SymbolRow s = new SymbolRow();
        s.repoId = repoId; s.fileId = fileId; s.kind = kind;
        s.pkg = "x"; s.simpleName = fqn.substring(fqn.lastIndexOf('.') + 1); s.fqn = fqn;
        return s;
    }

    private static SymbolRow method(long repoId, long fileId, SymbolRow parent, String name, String paramTypes) {
        SymbolRow s = new SymbolRow();
        s.repoId = repoId; s.fileId = fileId; s.kind = SymbolKind.METHOD;
        s.pkg = "x"; s.simpleName = name; s.fqn = parent.fqn + "#" + name;
        s.parentRef = parent; s.paramTypes = paramTypes;
        return s;
    }

    private static int countResolved(IndexStore store) throws Exception {
        try (Connection c = store.db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM edges WHERE resolution='ast' AND dst_symbol_id IS NOT NULL")) {
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /** Seeds: 1 EXTENDS edge (TYPE) and 1 CALLS edge (METHOD), both unresolved but resolvable. */
    private static long seed(IndexStore store, long repoId, long fileId) throws Exception {
        SymbolRow base = type(repoId, fileId, SymbolKind.CLASS, "x.Base");
        SymbolRow child = type(repoId, fileId, SymbolKind.CLASS, "x.Child");
        SymbolRow target = type(repoId, fileId, SymbolKind.CLASS, "x.Target");
        SymbolRow targetGreet = method(repoId, fileId, target, "greet", "");
        SymbolRow childCaller = method(repoId, fileId, child, "caller", "");
        store.insertSymbols(List.of(base, child, target, targetGreet, childCaller));

        EdgeRow extEdge = new EdgeRow();
        extEdge.repoId = repoId; extEdge.fileId = fileId; extEdge.kind = EdgeKind.EXTENDS;
        extEdge.srcSymbolId = child.id; extEdge.dstFqn = "x.Base"; extEdge.resolution = "unresolved";

        EdgeRow callEdge = new EdgeRow();
        callEdge.repoId = repoId; callEdge.fileId = fileId; callEdge.kind = EdgeKind.CALLS;
        callEdge.srcSymbolId = childCaller.id;
        callEdge.dstFqn = "x.Target"; callEdge.dstMember = "greet";
        callEdge.resolution = "unresolved";

        store.insertEdges(List.of(extEdge, callEdge));
        return childCaller.id;
    }

    @Test
    void linkerWritesParticipateInOuterTransactionAndRollBack(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = store.upsertFile(repoId, "Seed.java", 1L, "sha", 1L);
            seed(store, repoId, fileId);
            assertEquals(0, countResolved(store), "preconditions: nothing resolved yet");

            // Wrap the linker call in an outer transaction, then throw. If the
            // linker's UPDATE statements committed independently, the resolution
            // would survive the rollback. They must not.
            assertThrows(IllegalStateException.class, () ->
                store.runInWriteTransaction(() -> {
                    int linked = store.linkUnresolvedEdges(repoId);
                    assertTrue(linked >= 2,
                            "linker should have matched the EXTENDS and CALLS edges in-memory");
                    throw new IllegalStateException("simulated post-linker crash");
                }));

            assertEquals(0, countResolved(store),
                    "All linker UPDATEs must roll back as part of the outer transaction; "
                  + "if any pass committed independently, this would be > 0");
        } finally {
            store.db().close();
        }
    }

    @Test
    void linkerOnItsOwnCommitsAllPassesAtomically(@TempDir Path tmp) throws Exception {
        IndexStore store = openStore(tmp);
        try {
            long repoId = repo(store, tmp);
            long fileId = store.upsertFile(repoId, "Seed.java", 1L, "sha", 1L);
            seed(store, repoId, fileId);

            int linked = store.linkUnresolvedEdges(repoId);
            assertTrue(linked >= 2);
            assertEquals(linked, countResolved(store),
                    "Standalone linker call must produce a committed, fully-resolved state visible to readers");
        } finally {
            store.db().close();
        }
    }
}
