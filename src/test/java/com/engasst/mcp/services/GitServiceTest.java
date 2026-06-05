package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitServiceTest {

    @Test
    void historyAndCompareWorkOnTempRepo(@TempDir Path tmp) throws Exception {
        try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
            Path f = tmp.resolve("README.md");
            Files.writeString(f, "one\n");
            git.add().addFilepattern("README.md").call();
            var c1 = git.commit().setAuthor("a", "a@x").setMessage("first").call();

            Files.writeString(f, "one\ntwo\n");
            git.add().addFilepattern("README.md").call();
            var c2 = git.commit().setAuthor("a", "a@x").setMessage("second").call();

            var svc = new GitService(ServerConfig.load(tmp));

            var hist = svc.history("README.md", 10);
            assertEquals(2, hist.size());
            assertEquals("second", hist.get(0).message());

            var diff = svc.compare(c1.getName(), c2.getName(), 100);
            assertEquals(1, diff.size());
            assertEquals("MODIFY", diff.get(0).changeType());
            assertEquals(1, diff.get(0).addedLines());
        }
    }
}
