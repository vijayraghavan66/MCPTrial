package com.engasst.mcp.intel.pipeline;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.store.IndexStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the delta between the on-disk repository and what is recorded in
 * the index. Files are fingerprinted by (mtime, sha1-of-bytes) — mtime is the
 * fast path, sha is the tiebreaker for FS that round mtime (CI checkouts).
 */
public final class FileScanner {

    public record Plan(
            long repoId,
            List<Changed> added,
            List<Changed> modified,
            List<Long> removedFileIds,
            int totalDiskFiles
    ) {
        public int changedCount() { return added.size() + modified.size(); }
    }

    public record Changed(Path absolute, String relative, long mtime, String sha, long size) {}

    private final RepoWalker walker;
    private final ServerConfig config;
    private final IndexStore store;

    public FileScanner(RepoWalker walker, ServerConfig config, IndexStore store) {
        this.walker = walker;
        this.config = config;
        this.store = store;
    }

    public Plan scan(long repoId, Path repoRoot) throws Exception {
        Map<String, IndexStore.FileFingerprint> known = store.loadFileFingerprints(repoId);
        Set<String> seen = new HashSet<>();
        List<Changed> added = new ArrayList<>();
        List<Changed> modified = new ArrayList<>();

        walker.walk(repoRoot, walker::isJavaFile, file -> {
            try {
                String rel = config.workspaceRoot().relativize(file).toString().replace('\\', '/');
                long mtime = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                IndexStore.FileFingerprint prev = known.get(rel);
                if (prev != null && prev.mtime() == mtime) {
                    seen.add(rel);
                    return; // fast path: mtime unchanged, trust it
                }
                String sha = sha1(file);
                if (prev == null) {
                    added.add(new Changed(file, rel, mtime, sha, size));
                } else {
                    seen.add(rel);
                    if (!prev.sha().equals(sha)) {
                        modified.add(new Changed(file, rel, mtime, sha, size));
                    }
                }
            } catch (IOException ignored) {}
        });

        // Anything we knew about and didn't see on disk is removed
        List<Long> removed = new ArrayList<>();
        for (var e : known.entrySet()) {
            if (!seen.contains(e.getKey()) && !containsByRel(added, e.getKey())) {
                removed.add(e.getValue().fileId());
            }
        }
        int total = known.size() - removed.size() + added.size();
        return new Plan(repoId, added, modified, removed, total);
    }

    private static boolean containsByRel(List<Changed> list, String rel) {
        for (Changed c : list) if (c.relative.equals(rel)) return true;
        return false;
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(Files.readAllBytes(file));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }
}
