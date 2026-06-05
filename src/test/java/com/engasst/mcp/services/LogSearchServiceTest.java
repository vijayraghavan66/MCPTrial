package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LogSearchServiceTest {

    @Test
    void findsLogEntriesAndExtractsTimestamp(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("app.log");
        Files.writeString(log, """
                2026-01-02 03:04:05.123 INFO  starting
                2026-01-02 03:04:06.001 ERROR NullPointerException at Foo.bar
                2026-01-02 03:04:07.500 INFO  done
                """);
        var cfg = ServerConfig.load(tmp);
        var svc = new LogSearchService(new RepoWalker(cfg), cfg);

        var hits = svc.search("ERROR", false, false, null, 10);
        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).line());
        assertNotNull(hits.get(0).timestamp());
        assertTrue(hits.get(0).timestamp().startsWith("2026-01-02"));
    }
}
