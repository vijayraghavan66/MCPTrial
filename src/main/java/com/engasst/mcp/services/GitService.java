package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.model.CommitInfo;
import com.engasst.mcp.model.DiffEntryInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class GitService {

    private final ServerConfig config;

    public GitService(ServerConfig config) {
        this.config = config;
    }

    private Repository openRepo() throws IOException {
        Path gitDir = config.workspaceRoot().resolve(".git");
        if (!Files.isDirectory(gitDir) && !Files.isRegularFile(gitDir)) {
            throw new IOException("Not a git repository: " + config.workspaceRoot());
        }
        return new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .findGitDir()
                .build();
    }

    public List<CommitInfo> history(String filePath, int maxCount) throws Exception {
        List<CommitInfo> out = new ArrayList<>();
        int limit = maxCount <= 0 ? 50 : Math.min(maxCount, 1000);
        try (Repository repo = openRepo(); Git git = new Git(repo)) {
            var cmd = git.log().setMaxCount(limit);
            if (filePath != null && !filePath.isBlank()) {
                cmd.addPath(filePath.replace('\\', '/'));
            }
            for (RevCommit c : cmd.call()) {
                out.add(toInfo(c));
            }
        }
        return out;
    }

    public List<DiffEntryInfo> compare(String fromRev, String toRev, int maxPatchLines) throws Exception {
        List<DiffEntryInfo> out = new ArrayList<>();
        int patchLimit = maxPatchLines <= 0 ? 400 : maxPatchLines;
        try (Repository repo = openRepo()) {
            ObjectId from = repo.resolve(fromRev + "^{tree}");
            ObjectId to   = repo.resolve(toRev   + "^{tree}");
            if (from == null) throw new IllegalArgumentException("Unknown revision: " + fromRev);
            if (to == null)   throw new IllegalArgumentException("Unknown revision: " + toRev);

            try (RevWalk rw = new RevWalk(repo);
                 ByteArrayOutputStream buf = new ByteArrayOutputStream();
                 DiffFormatter df = new DiffFormatter(buf)) {
                df.setRepository(repo);
                df.setDetectRenames(true);

                RevTree fromTree = rw.parseTree(from);
                RevTree toTree   = rw.parseTree(to);
                CanonicalTreeParser fp = new CanonicalTreeParser();
                CanonicalTreeParser tp = new CanonicalTreeParser();
                try (var reader = repo.newObjectReader()) {
                    fp.reset(reader, fromTree.getId());
                    tp.reset(reader, toTree.getId());
                }
                List<DiffEntry> diffs = df.scan(fp, tp);
                for (DiffEntry d : diffs) {
                    int added = 0, deleted = 0;
                    for (Edit e : df.toFileHeader(d).toEditList()) {
                        added   += e.getEndB() - e.getBeginB();
                        deleted += e.getEndA() - e.getBeginA();
                    }
                    buf.reset();
                    df.format(d);
                    String patch = truncateLines(buf.toString(StandardCharsets.UTF_8), patchLimit);
                    out.add(new DiffEntryInfo(
                            d.getChangeType().name(),
                            d.getOldPath(),
                            d.getNewPath(),
                            added, deleted, patch));
                }
            }
        }
        return out;
    }

    private static CommitInfo toInfo(RevCommit c) {
        var ident = c.getAuthorIdent();
        String iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochSecond(c.getCommitTime()).atOffset(ZoneOffset.UTC));
        return new CommitInfo(
                c.getName(),
                c.getName().substring(0, Math.min(8, c.getName().length())),
                ident == null ? "" : ident.getName(),
                ident == null ? "" : ident.getEmailAddress(),
                iso,
                c.getFullMessage().trim());
    }

    private static String truncateLines(String s, int maxLines) {
        int count = 0, idx = 0;
        while (count < maxLines) {
            int nl = s.indexOf('\n', idx);
            if (nl < 0) return s;
            idx = nl + 1; count++;
        }
        return s.substring(0, idx) + "... [truncated]";
    }
}
