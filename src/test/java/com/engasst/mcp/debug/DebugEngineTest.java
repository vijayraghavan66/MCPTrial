package com.engasst.mcp.debug;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.debug.model.Finding;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.debug.model.ParsedStackTrace;
import com.engasst.mcp.debug.parser.StackTraceParser;
import com.engasst.mcp.debug.rootcause.RootCauseEngine;
import com.engasst.mcp.debug.score.ScoringEngine;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.services.GitService;
import com.engasst.mcp.services.LogSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the Debug Intelligence Engine end-to-end against a tiny
 * indexed repo. Git-dependent paths short-circuit cleanly when no .git
 * directory is present, so the assertions here are git-free.
 */
class DebugEngineTest {

    @Test
    void parsesStackTraceFramesAndExceptionChain() {
        StackTraceParser p = new StackTraceParser();
        ParsedStackTrace ps = p.parse("""
                java.lang.NoSuchMethodError: org.bouncycastle.jce.provider.BouncyCastleProvider.someMethod()
                \tat com.example.svc.CertificateService.issue(CertificateService.java:42)
                \tat com.example.web.CertificateController.create(CertificateController.java:18)
                \tat sun.reflect.NativeMethodAccessorImpl.invoke(Native Method)
                Caused by: java.lang.RuntimeException: bad config
                \tat com.example.svc.CertificateService.issue(CertificateService.java:40)
                """);
        assertEquals(2, ps.chain.size());
        assertEquals("java.lang.NoSuchMethodError", ps.primary().exceptionFqn);
        assertEquals("java.lang.RuntimeException", ps.rootCause().exceptionFqn);
        assertEquals(4, ps.frames.size());
        assertEquals("com.example.svc.CertificateService", ps.frames.get(0).classFqn);
        assertEquals("issue", ps.frames.get(0).method);
        assertEquals(42, ps.frames.get(0).lineNumber);
        assertNull(ps.frames.get(2).lineNumber); // Native Method
    }

    @Test
    void rootCauseEngineClassifiesCommonExceptions() {
        RootCauseEngine rc = new RootCauseEngine();
        ParsedStackTrace ps = new ParsedStackTrace("x");
        ps.chain.add(new ParsedStackTrace.ExceptionLink("java.lang.NoSuchMethodError", "X.y()"));
        var causes = rc.classify(ps);
        assertFalse(causes.isEmpty());
        assertEquals("CLASSPATH_MISMATCH", causes.get(0).category);
        assertTrue(causes.get(0).suggestedSteps.size() >= 2);
    }

    @Test
    void scoringFactorsAreInRange() {
        ScoringEngine s = new ScoringEngine();
        assertEquals(100.0, s.frameDistance(0), 1e-9);
        assertTrue(s.frameDistance(5) < s.frameDistance(2));
        assertEquals(0.0, s.commitRecency(365));
        assertEquals(100.0, s.commitRecency(0));
        assertEquals(0.0, s.historicalFailure(0));
        assertTrue(s.historicalFailure(20) > 80);
        double combo = s.combine(100, 50, 50, 100, 0);
        assertTrue(combo > 0 && combo <= 100);
    }

    @Test
    void analyzeStackTraceCorrelatesAgainstIndex(@TempDir Path tmp) throws Exception {
        IntelEngine intel = bootstrap(tmp);
        try {
            intel.pipeline().build(tmp, false);

            var cfg = ServerConfig.load(tmp);
            DebugEngine debug = new DebugEngine(intel, new GitService(cfg),
                                                new LogSearchService(new RepoWalker(cfg), cfg));

            String stack = """
                    java.lang.NullPointerException: item was null
                    \tat com.example.OrderService.submit(OrderService.java:7)
                    \tat com.example.OrderApi.create(OrderApi.java:9)
                    """;
            InvestigationReport rep = debug.analyzeStackTrace(stack);

            assertEquals("STACK_TRACE", rep.inputKind);
            assertNotNull(rep.parsedStack);
            assertTrue(rep.parsedStack.frames.size() >= 2);
            // Both frames should resolve into the indexed repo.
            assertTrue(rep.parsedStack.frames.get(0).inRepo, "OrderService.submit should be in-repo");
            assertTrue(rep.parsedStack.frames.get(1).inRepo, "OrderApi.create should be in-repo");

            // Findings: at least RELEVANT_METHOD and EXECUTION_PATH entries.
            assertTrue(rep.findings.stream().anyMatch(f -> f.kind == Finding.Kind.RELEVANT_METHOD));
            assertTrue(rep.findings.stream().anyMatch(f -> f.kind == Finding.Kind.EXECUTION_PATH));

            // Root cause classified as NULL_DEREFERENCE.
            assertFalse(rep.rootCauses.isEmpty());
            assertEquals("NULL_DEREFERENCE", rep.rootCauses.get(0).category);
            assertTrue(rep.overallConfidence > 0);

            // Second call with same signature should pick up historical correlation.
            InvestigationReport rep2 = debug.analyzeStackTrace(stack);
            assertTrue(rep2.findings.stream().anyMatch(f -> f.kind == Finding.Kind.HISTORICAL_FAILURE),
                    "Second run should flag historical correlation");
        } finally {
            intel.close();
        }
    }

    @Test
    void investigateFailureFindsExecutionPath(@TempDir Path tmp) throws Exception {
        IntelEngine intel = bootstrap(tmp);
        try {
            intel.pipeline().build(tmp, false);
            var cfg = ServerConfig.load(tmp);
            DebugEngine debug = new DebugEngine(intel, new GitService(cfg),
                                                new LogSearchService(new RepoWalker(cfg), cfg));

            InvestigationReport rep = debug.investigateFailure("OrderService", "submit", 5);
            assertEquals("METHOD", rep.inputKind);
            assertTrue(rep.findings.stream().anyMatch(f -> f.kind == Finding.Kind.EXECUTION_PATH),
                    "Should produce an execution-path finding rooted at OrderService.submit");
        } finally {
            intel.close();
        }
    }

    @Test
    void investigateExceptionWorksWithoutStack(@TempDir Path tmp) throws Exception {
        IntelEngine intel = bootstrap(tmp);
        try {
            var cfg = ServerConfig.load(tmp);
            DebugEngine debug = new DebugEngine(intel, new GitService(cfg),
                                                new LogSearchService(new RepoWalker(cfg), cfg));
            InvestigationReport rep = debug.investigateException("java.lang.OutOfMemoryError");
            assertEquals("EXCEPTION", rep.inputKind);
            assertFalse(rep.rootCauses.isEmpty());
            assertEquals("MEMORY_PRESSURE", rep.rootCauses.get(0).category);
        } finally {
            intel.close();
        }
    }

    // ---------------------------------------------------------- bootstrap

    private IntelEngine bootstrap(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("OrderApi.java"), """
                package com.example;
                public class OrderApi {
                    private final OrderService svc = new OrderService();
                    public String create() { return svc.submit("widget"); }
                }
                """);
        Files.writeString(src.resolve("OrderService.java"), """
                package com.example;
                public class OrderService {
                    public String submit(String item) {
                        return item.toUpperCase();
                    }
                }
                """);
        var cfg = ServerConfig.load(tmp);
        return new IntelEngine(cfg, new RepoWalker(cfg), new ParsedFileCache());
    }
}
