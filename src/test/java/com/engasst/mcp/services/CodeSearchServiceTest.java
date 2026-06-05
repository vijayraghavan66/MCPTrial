package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.model.SearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeSearchServiceTest {

    @Test
    void findsTextHitsAndSkipsIgnoredDirs(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Hello.java"),
                "package x;\nclass Hello { String s = \"needle\"; }\n");
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.writeString(target.resolve("ignored.java"), "needle should be skipped");

        var cfg = ServerConfig.load(tmp);
        var svc = new CodeSearchService(new RepoWalker(cfg), cfg);

        List<SearchHit> hits = svc.search("needle", false, false, List.of(), null, 50);
        assertEquals(1, hits.size(), "Should ignore target/ directory");
        assertEquals(2, hits.get(0).line());
        assertTrue(hits.get(0).file().endsWith("Hello.java"));
    }

    @Test
    void regexAndExtensionFilterWork(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.java"), "int x = 42;");
        Files.writeString(tmp.resolve("b.md"),   "the answer is 42");

        var cfg = ServerConfig.load(tmp);
        var svc = new CodeSearchService(new RepoWalker(cfg), cfg);

        var javaOnly = svc.search("\\d+", true, false, List.of("java"), null, 10);
        assertEquals(1, javaOnly.size());
        assertTrue(javaOnly.get(0).file().endsWith("a.java"));
    }
}
