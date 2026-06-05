package com.engasst.mcp.intel.pipeline;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.model.IndexStatus;
import com.engasst.mcp.intel.store.IndexStore;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Top-level orchestrator: SCANNING → PARSING → LINKING → DONE.
 *
 * <p>Concurrency: only one index build per repo at a time (gated by
 * {@link #building}). Within a build, files are parsed sequentially on the
 * caller thread — writes to SQLite are already the bottleneck and SQLite's
 * single-writer model means parallel writers gain nothing. (Parallel parsing
 * with a thread-safe queue is the obvious next optimisation; see README.)
 */
public final class IndexingPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(IndexingPipeline.class);

    private final RepoWalker walker;
    private final ServerConfig config;
    private final IndexStore store;
    private final ParsedFileCache cache;
    private final FileScanner scanner;
    private final AtomicBoolean building = new AtomicBoolean(false);

    public IndexingPipeline(RepoWalker walker, ServerConfig config, IndexStore store, ParsedFileCache cache) {
        this.walker = walker;
        this.config = config;
        this.store = store;
        this.cache = cache;
        this.scanner = new FileScanner(walker, config, store);
        configureSolver();
    }

    private void configureSolver() {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver());
        for (Path root : discoverSourceRoots()) {
            try { ts.add(new JavaParserTypeSolver(root)); } catch (RuntimeException ignored) {}
        }
        cache.configure(new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(ts))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    }

    private List<Path> discoverSourceRoots() {
        try (var stream = Files.walk(config.workspaceRoot(), 6)) {
            return stream.filter(Files::isDirectory)
                    .filter(d -> {
                        String s = d.toString().replace('\\', '/');
                        return s.endsWith("/src/main/java") || s.endsWith("/src/test/java");
                    }).toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public IndexStatus build(Path repoRoot, boolean incremental) throws Exception {
        if (!building.compareAndSet(false, true)) {
            throw new IllegalStateException("Index build already in progress");
        }
        long started = System.currentTimeMillis();
        long repoId = -1L;
        try {
            // upsertRepo *must* live inside the try: without it, an exception
            // here would leave `building=true` permanently and wedge the engine.
            repoId = store.upsertRepo(repoRoot.toAbsolutePath().normalize().toString());

            // BUILDING is the macro state for "do not trust this index right now".
            // phase=SCANNING is the fine-grained sub-state for progress UIs.
            store.updateStatus(repoId, "BUILDING", "SCANNING", null, null, 0, started, null, null);

            FileScanner.Plan plan = scanner.scan(repoId, repoRoot);
            if (!incremental) {
                // Full rebuild: drop everything we knew, treat all on-disk files as new
                for (var fp : store.loadFileFingerprints(repoId).values()) store.deleteFile(fp.fileId());
                plan = scanner.scan(repoId, repoRoot);
            }
            store.updateStatus(repoId, null, "PARSING", plan.totalDiskFiles(), plan.changedCount(),
                    0, null, null, null);
            LOG.info("Index build repo={} added={} modified={} removed={}",
                    repoId, plan.added().size(), plan.modified().size(), plan.removedFileIds().size());

            for (Long fid : plan.removedFileIds()) store.deleteFile(fid);

            AtomicInteger done = new AtomicInteger(0);
            for (FileScanner.Changed ch : plan.added()) indexFile(repoId, ch, done, plan.changedCount());
            for (FileScanner.Changed ch : plan.modified()) indexFile(repoId, ch, done, plan.changedCount());

            store.updateStatus(repoId, null, "LINKING", null, null, null, null, null, null);
            int linked = store.linkUnresolvedEdges(repoId);
            LOG.info("Linker resolved {} dangling edges", linked);

            store.refreshCounters(repoId);
            store.markRepoIndexed(repoId);
            store.updateStatus(repoId, "COMPLETED", "DONE", null, null, null, null,
                    System.currentTimeMillis(), null);
            return store.status(repoId).orElseThrow();
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) so Errors -- OOM, StackOverflow,
            // schema corruption surfacing as AssertionError -- still transition the
            // build out of BUILDING. Without this, an Error would leave `state`
            // stuck at BUILDING and readers would block waiting forever.
            if (repoId > 0) {
                try {
                    store.updateStatus(repoId, "FAILED", "ERROR", null, null, null, null,
                            System.currentTimeMillis(),
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                } catch (Exception logOnly) {
                    LOG.error("Failed to record build failure status for repo={}", repoId, logOnly);
                }
            }
            if (t instanceof Exception ex) throw ex;
            if (t instanceof Error er)     throw er;
            throw new RuntimeException(t);
        } finally {
            // Must run regardless of success, checked failure, unchecked failure,
            // Error, or even an exception while writing the FAILED status above.
            // Otherwise `building` stays true and every future build returns
            // "Index build already in progress" until process restart.
            building.set(false);
        }
    }

    private void indexFile(long repoId, FileScanner.Changed ch,
                           AtomicInteger done, int total) throws Exception {
        // --- Phase 1: parse + extract (NO write lock held, NO transaction). ---
        // JavaParser + SymbolExtractor are CPU-bound and can take hundreds of
        // milliseconds on large files. Doing them inside the write transaction
        // would pin the SQLite write lock and stall every other writer.
        Optional<CompilationUnit> opt = cache.parse(ch.absolute());
        SymbolExtractor.Extracted ex;
        if (opt.isPresent()) {
            // SymbolExtractor needs a file_id, but we don't have one yet. It
            // only uses fileId to stamp SymbolRow.fileId / EdgeRow.fileId, so
            // we extract with a sentinel and patch the rows once the row exists.
            ex = SymbolExtractor.extract(opt.get(), repoId, -1L);
        } else {
            ex = null;
        }

        // --- Phase 2: SQL writes (atomic per-file). ---
        // Body is intentionally minimal: only the 7 store operations specified
        // by the P0-2 contract. Any failure here rolls back upsertFile,
        // purgeFileContents, insertImport, insertSymbols, insertAnnotation,
        // insertEdges, and updateStatus together.
        store.runInWriteTransaction(() -> {
            long fileId = store.upsertFile(repoId, ch.relative(), ch.mtime(), ch.sha(), ch.size());
            store.purgeFileContents(fileId);
            if (ex == null) {
                done.incrementAndGet();
                return;
            }
            // Imports
            for (var ir : ex.imports()) {
                store.insertImport(fileId, ir.fqn(), ir.isStatic(), ir.isWildcard());
            }
            // Patch the sentinel fileId on rows produced by the extractor.
            for (var s : ex.symbols()) s.fileId = fileId;
            for (var e : ex.edges())   e.fileId = fileId;
            // Symbols: insert in order so parent ids precede child ids
            store.insertSymbols(ex.symbols());
            // Annotations now that target.id is set
            for (var a : ex.annotations()) {
                store.insertAnnotation(a.target().id, a.name(), a.fqn(), a.argsJson());
            }
            // Back-fill srcSymbolId from srcRef and insert edges
            for (var e : ex.edges()) {
                if (e.srcRef != null) e.srcSymbolId = e.srcRef.id;
            }
            store.insertEdges(ex.edges());

            int n = done.incrementAndGet();
            if (n % 250 == 0 || n == total) {
                store.updateStatus(repoId, null, null, null, null, n, null, null, null);
            }
        });
    }
}
