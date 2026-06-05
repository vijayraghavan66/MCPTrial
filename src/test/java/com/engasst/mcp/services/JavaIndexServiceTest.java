package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaIndexServiceTest {

    private JavaIndexService bootstrap(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/com/example"));

        Files.writeString(src.resolve("Greeter.java"), """
                package com.example;
                public interface Greeter {
                    String greet(String name);
                }
                """);

        Files.writeString(src.resolve("HelloGreeter.java"), """
                package com.example;
                public class HelloGreeter implements Greeter {
                    @Override public String greet(String name) {
                        return "hi " + name;
                    }
                }
                """);

        Files.writeString(src.resolve("App.java"), """
                package com.example;
                public class App {
                    public void run() {
                        Greeter g = new HelloGreeter();
                        System.out.println(g.greet("world"));
                    }
                }
                """);

        var cfg = ServerConfig.load(tmp);
        return new JavaIndexService(new RepoWalker(cfg), new ParsedFileCache(), cfg);
    }

    @Test
    void findsClassesByName(@TempDir Path tmp) throws Exception {
        var svc = bootstrap(tmp);
        var classes = svc.findClass("Greeter", true);
        assertEquals(1, classes.size());
        assertEquals("com.example.Greeter", classes.get(0).fullyQualifiedName());
        assertEquals("interface", classes.get(0).kind());
    }

    @Test
    void findsMethods(@TempDir Path tmp) throws Exception {
        var svc = bootstrap(tmp);
        var methods = svc.findMethod("greet", null, true);
        assertEquals(1, methods.size());
        assertEquals("com.example.HelloGreeter", methods.get(0).declaringClass());
    }

    @Test
    void findsImplementations(@TempDir Path tmp) throws Exception {
        var svc = bootstrap(tmp);
        var impls = svc.findImplementations("Greeter");
        assertEquals(1, impls.size());
        assertEquals("com.example.HelloGreeter", impls.get(0).fullyQualifiedName());
        assertEquals("implements", impls.get(0).relation());
    }

    @Test
    void findsCallers(@TempDir Path tmp) throws Exception {
        var svc = bootstrap(tmp);
        var callers = svc.findCallers("Greeter", "greet");
        assertFalse(callers.isEmpty(), "expected at least one call site");
        assertEquals("com.example.App", callers.get(0).callerClass());
        assertEquals("run", callers.get(0).callerMethod());
    }
}
