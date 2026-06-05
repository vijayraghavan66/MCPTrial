package com.engasst.mcp.intel;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.intel.model.IndexStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1: end-to-end coverage for constructor edge resolution.
 *
 * <p>Each test scaffolds a tiny repo, builds the index, then asserts directly
 * against the SQLite store. The assertions exercise:
 * <ul>
 *   <li>Default constructor synthesis for classes with no explicit ctor.</li>
 *   <li>{@code NEW} edge resolution to the synthesised default ctor.</li>
 *   <li>Overload disambiguation via {@code param_types}.</li>
 *   <li>{@code super(...)} explicit constructor invocation as a NEW edge.</li>
 *   <li>Interfaces / annotations do NOT receive synthesised ctors.</li>
 *   <li>Linker idempotency across a full rebuild.</li>
 * </ul>
 */
class ConstructorResolutionTest {

    private static IntelEngine engine(Path tmp) throws Exception {
        var cfg = ServerConfig.load(tmp);
        var walker = new RepoWalker(cfg);
        var cache = new ParsedFileCache();
        return new IntelEngine(cfg, walker, cache);
    }

    private static Path src(Path tmp) throws Exception {
        return Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    }

    @Test
    void defaultConstructorIsSynthesizedForClassWithoutExplicitCtor(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("Pojo.java"), """
                package com.example;
                public class Pojo {}
                """);

        try (IntelEngine eng = engine(tmp)) {
            IndexStatus st = eng.pipeline().build(tmp, false);
            assertEquals("DONE", st.phase());

            assertEquals(1, countCtors(eng, "com.example.Pojo"),
                    "Pojo with no explicit ctor must get a synthetic default ctor");
            assertEquals("",  ctorParamTypes(eng, "com.example.Pojo"),
                    "Synthetic default ctor must have empty param_types");
        }
    }

    @Test
    void interfacesAndAnnotationsDoNotGetSyntheticConstructors(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("Stuff.java"), """
                package com.example;
                public interface Stuff {}
                """);
        Files.writeString(src.resolve("Marker.java"), """
                package com.example;
                public @interface Marker {}
                """);

        try (IntelEngine eng = engine(tmp)) {
            eng.pipeline().build(tmp, false);
            assertEquals(0, countCtors(eng, "com.example.Stuff"));
            assertEquals(0, countCtors(eng, "com.example.Marker"));
        }
    }

    @Test
    void newEdgeResolvesToImplicitDefaultConstructor(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("Bean.java"), """
                package com.example;
                public class Bean {}
                """);
        Files.writeString(src.resolve("Holder.java"), """
                package com.example;
                public class Holder {
                    public Bean make() { return new Bean(); }
                }
                """);

        try (IntelEngine eng = engine(tmp)) {
            eng.pipeline().build(tmp, false);
            assertTrue(newEdgeIsResolved(eng, "com.example.Bean"),
                    "new Bean() must resolve to synthetic default ctor of Bean");
        }
    }

    @Test
    void newEdgeResolvesCorrectOverloadByParamTypes(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("Box.java"), """
                package com.example;
                public class Box {
                    public Box() {}
                    public Box(String label) { /* labelled */ }
                    public Box(int count, String label) { /* mixed */ }
                }
                """);
        Files.writeString(src.resolve("Factory.java"), """
                package com.example;
                public class Factory {
                    public Box noArg()      { return new Box(); }
                    public Box withLabel()  { return new Box("a"); }
                    public Box mixed()      { return new Box(1, "a"); }
                }
                """);

        try (IntelEngine eng = engine(tmp)) {
            eng.pipeline().build(tmp, false);

            try (Connection c = eng.store().db().readConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT e.param_types, m.param_types " +
                         "FROM edges e JOIN symbols m ON m.id = e.dst_symbol_id " +
                         "WHERE e.kind='NEW' AND e.dst_fqn='com.example.Box' AND m.kind='CONSTRUCTOR'");
                 ResultSet rs = ps.executeQuery()) {
                int matched = 0;
                while (rs.next()) {
                    String edgeParams = rs.getString(1);
                    String ctorParams = rs.getString(2);
                    assertEquals(edgeParams, ctorParams,
                            "Each NEW edge must resolve to the ctor with matching param_types");
                    matched++;
                }
                assertEquals(3, matched,
                        "All three NEW edges (no-arg, String, int+String) must resolve to distinct overloads");
            }
        }
    }

    @Test
    void superConstructorInvocationIsRecordedAsNewEdge(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("Base.java"), """
                package com.example;
                public class Base {
                    public Base(String name) {}
                }
                """);
        Files.writeString(src.resolve("Sub.java"), """
                package com.example;
                public class Sub extends Base {
                    public Sub() { super("hi"); }
                }
                """);

        try (IntelEngine eng = engine(tmp)) {
            eng.pipeline().build(tmp, false);

            try (Connection c = eng.store().db().readConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM edges e " +
                         "JOIN symbols src ON src.id = e.src_symbol_id " +
                         "JOIN symbols parent ON parent.id = src.parent_id " +
                         "WHERE e.kind='NEW' AND e.dst_fqn='com.example.Base' " +
                         "  AND parent.fqn='com.example.Sub' AND src.kind='CONSTRUCTOR'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1),
                        "super(\"hi\") must produce a NEW edge from Sub.<init> to Base.<init>");
            }
        }
    }

    @Test
    void linkerIsIdempotentAcrossFullRebuild(@TempDir Path tmp) throws Exception {
        Path src = src(tmp);
        Files.writeString(src.resolve("A.java"), """
                package com.example;
                public class A {}
                """);
        Files.writeString(src.resolve("B.java"), """
                package com.example;
                public class B {
                    public A make() { return new A(); }
                }
                """);

        try (IntelEngine eng = engine(tmp)) {
            eng.pipeline().build(tmp, false);
            int linkedFirst  = countResolvedNewEdges(eng, "com.example.A");
            eng.pipeline().build(tmp, false); // full rebuild
            int linkedSecond = countResolvedNewEdges(eng, "com.example.A");
            assertEquals(linkedFirst, linkedSecond,
                    "Full rebuild must produce the same number of resolved NEW edges");
            assertTrue(linkedFirst >= 1, "At least one resolved NEW edge expected");
        }
    }

    // ----------------------------------------------------------------- helpers

    private static int countCtors(IntelEngine eng, String classFqn) throws Exception {
        try (Connection c = eng.store().db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM symbols m JOIN symbols t ON t.id = m.parent_id " +
                     "WHERE t.fqn=? AND m.kind='CONSTRUCTOR'")) {
            ps.setString(1, classFqn);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static String ctorParamTypes(IntelEngine eng, String classFqn) throws Exception {
        try (Connection c = eng.store().db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT m.param_types FROM symbols m JOIN symbols t ON t.id = m.parent_id " +
                     "WHERE t.fqn=? AND m.kind='CONSTRUCTOR' LIMIT 1")) {
            ps.setString(1, classFqn);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    private static boolean newEdgeIsResolved(IntelEngine eng, String dstFqn) throws Exception {
        try (Connection c = eng.store().db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM edges WHERE kind='NEW' AND dst_fqn=? " +
                     "  AND dst_symbol_id IS NOT NULL AND resolution='ast'")) {
            ps.setString(1, dstFqn);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) > 0; }
        }
    }

    private static int countResolvedNewEdges(IntelEngine eng, String dstFqn) throws Exception {
        try (Connection c = eng.store().db().readConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM edges WHERE kind='NEW' AND dst_fqn=? " +
                     "  AND dst_symbol_id IS NOT NULL")) {
            ps.setString(1, dstFqn);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
