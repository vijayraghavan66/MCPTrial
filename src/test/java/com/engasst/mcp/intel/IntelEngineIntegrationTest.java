package com.engasst.mcp.intel;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.model.IndexStatus;
import com.engasst.mcp.intel.query.EntryPointService;
import com.engasst.mcp.intel.query.TraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: scaffold a tiny Spring-like repo, index it, then exercise
 * traceExecutionFlow / explain / entry points / DB access paths.
 */
class IntelEngineIntegrationTest {

    private IntelEngine bootstrap(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("OrderController.java"), """
                package com.example;
                public @interface RestController {}
                """);
        Files.writeString(src.resolve("PostMapping.java"), """
                package com.example;
                public @interface PostMapping { String value() default ""; }
                """);
        Files.writeString(src.resolve("Service.java"), """
                package com.example;
                public @interface Service {}
                """);
        Files.writeString(src.resolve("Repository.java"), """
                package com.example;
                public @interface Repository {}
                """);

        Files.writeString(src.resolve("OrderApi.java"), """
                package com.example;

                @RestController
                public class OrderApi {
                    private final OrderService svc = new OrderService();

                    @PostMapping("/orders")
                    public String create() {
                        return svc.submit("widget");
                    }
                }
                """);

        Files.writeString(src.resolve("OrderService.java"), """
                package com.example;

                @Service
                public class OrderService {
                    private final OrderRepo repo = new OrderRepo();
                    public String submit(String item) {
                        CryptoUtil.sign(item);
                        return repo.save(item);
                    }
                }
                """);

        Files.writeString(src.resolve("OrderRepo.java"), """
                package com.example;

                @Repository
                public class OrderRepo {
                    public String save(String item) { return "saved:" + item; }
                }
                """);

        Files.writeString(src.resolve("CryptoUtil.java"), """
                package com.example;
                public class CryptoUtil {
                    public static String sign(String s) { return s + ":signed"; }
                }
                """);

        var cfg = ServerConfig.load(tmp);
        var walker = new RepoWalker(cfg);
        var cache = new ParsedFileCache();
        return new IntelEngine(cfg, walker, cache);
    }

    @Test
    void indexesAndAnswersAllIntelTools(@TempDir Path tmp) throws Exception {
        try (IntelEngine engine = bootstrap(tmp)) {
            IndexStatus st = engine.pipeline().build(tmp, false);
            assertEquals("DONE", st.phase());
            assertTrue(st.symbolsCount() > 0, "Should have indexed some symbols");
            assertTrue(st.edgesCount() > 0,   "Should have produced some edges");

            // traceExecutionFlow: OrderApi.create -> OrderService.submit -> {CryptoUtil.sign, OrderRepo.save}
            List<TraceService.FlowNode> flow = engine.trace().traceExecutionFlow("OrderApi", "create", 6);
            assertFalse(flow.isEmpty());
            assertTrue(flow.stream().anyMatch(n -> "submit".equals(n.method())));
            assertTrue(flow.stream().anyMatch(n -> "sign".equals(n.method())));
            assertTrue(flow.stream().anyMatch(n -> "save".equals(n.method())));

            // explain
            Optional<Map<String, Object>> exp = engine.explain().explain("OrderService", "submit");
            assertTrue(exp.isPresent());
            assertEquals("com.example.OrderService", exp.get().get("class"));

            // entry points: should include the controller endpoint
            List<EntryPointService.EntryPoint> eps = engine.entries().findServiceEntryPoints();
            assertTrue(eps.stream().anyMatch(e -> "create".equals(e.method())),
                    "Expected create() as an entry point");

            // controller flows
            List<Map<String, Object>> flows = engine.entries().findControllerFlows(6);
            assertEquals(1, flows.size());

            // dependency path: OrderApi -> OrderRepo
            var hops = engine.trace().findDependencyPath("OrderApi", "OrderRepo", 6);
            assertFalse(hops.isEmpty(), "Should find a dep path from OrderApi to OrderRepo");

            // db access paths: at least one (OrderApi -> ... -> OrderRepo[@Repository])
            var paths = engine.entries().findDatabaseAccessPaths(8);
            assertFalse(paths.isEmpty(), "Expected at least one DB access path via @Repository");
        }
    }

    @Test
    void incrementalReindexOnlyReparsesChangedFiles(@TempDir Path tmp) throws Exception {
        try (IntelEngine engine = bootstrap(tmp)) {
            IndexStatus first = engine.pipeline().build(tmp, true);
            assertEquals(first.filesTotal(), first.filesChanged());

            // Touch one file
            Path f = tmp.resolve("src/main/java/com/example/CryptoUtil.java");
            String body = Files.readString(f);
            Files.writeString(f, body + "\n// touched\n");
            // bump mtime explicitly (Windows FS rounding)
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

            IndexStatus second = engine.pipeline().build(tmp, true);
            assertEquals("DONE", second.phase());
            assertEquals(1, second.filesChanged(), "Only the touched file should be re-indexed");
        }
    }
}
