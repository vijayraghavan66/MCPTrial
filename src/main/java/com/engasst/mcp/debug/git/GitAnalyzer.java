package com.engasst.mcp.debug.git;

import com.engasst.mcp.model.CommitInfo;
import com.engasst.mcp.services.GitService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git-aware lookups for the debug engine. Wraps {@link GitService} so the
 * debug subsystem does not duplicate JGit setup, while adding analytics
 * (recency / frequency) computed over the returned commits.
 */
public final class GitAnalyzer {

    public record FileChange(String path, List<CommitInfo> commits,
                             long daysSinceLast, int commitsLast90Days) {}

    private final GitService git;
    private final int historyLimit;

    public GitAnalyzer(GitService git) { this(git, 50); }

    public GitAnalyzer(GitService git, int historyLimit) {
        this.git = git;
        this.historyLimit = historyLimit;
    }

    public boolean gitAvailable() {
        try { git.history("", 1); return true; }
        catch (Exception ex) { return false; }
    }

    /** History + analytics for a single file. Returns null on git failure. */
    public FileChange forFile(String repoRelativePath) {
        if (repoRelativePath == null || repoRelativePath.isBlank()) return null;
        try {
            List<CommitInfo> commits = git.history(repoRelativePath, historyLimit);
            return new FileChange(
                    repoRelativePath, commits,
                    daysSinceLast(commits), commitsWithin(commits, 90));
        } catch (Exception ex) {
            return null;
        }
    }

    /** Run {@link #forFile} for every distinct path, preserving order. */
    public Map<String, FileChange> forFiles(Collection<String> paths) {
        Map<String, FileChange> out = new LinkedHashMap<>();
        for (String p : paths) {
            if (p == null || out.containsKey(p)) continue;
            FileChange ch = forFile(p);
            if (ch != null) out.put(p, ch);
        }
        return out;
    }

    /** Most recent commit timestamp across a group of paths, in epoch days. */
    public long daysSinceLast(List<CommitInfo> commits) {
        long latest = 0;
        for (CommitInfo c : commits) {
            long epoch = parseEpoch(c.date());
            if (epoch > latest) latest = epoch;
        }
        if (latest == 0) return Long.MAX_VALUE;
        long now = System.currentTimeMillis() / 1000L;
        return Math.max(0, (now - latest) / 86_400L);
    }

    public int commitsWithin(List<CommitInfo> commits, int days) {
        long cutoff = (System.currentTimeMillis() / 1000L) - (long) days * 86_400L;
        int n = 0;
        for (CommitInfo c : commits) if (parseEpoch(c.date()) >= cutoff) n++;
        return n;
    }

    /** Flatten {@link FileChange#commits()} for a set of files, deduped by SHA, newest first. */
    public List<CommitInfo> mergedCommits(Collection<FileChange> changes) {
        Map<String, CommitInfo> bySha = new LinkedHashMap<>();
        List<CommitInfo> all = new ArrayList<>();
        for (FileChange c : changes) all.addAll(c.commits());
        all.sort((a, b) -> Long.compare(parseEpoch(b.date()), parseEpoch(a.date())));
        for (CommitInfo c : all) bySha.putIfAbsent(c.hash(), c);
        return new ArrayList<>(bySha.values());
    }

    private static long parseEpoch(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try { return OffsetDateTime.parse(iso).toEpochSecond(); }
        catch (Exception ex) {
            try { return Instant.parse(iso).getEpochSecond(); }
            catch (Exception ignored) { return 0; }
        }
    }

    @SuppressWarnings("unused")
    private static OffsetDateTime nowUtc() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
