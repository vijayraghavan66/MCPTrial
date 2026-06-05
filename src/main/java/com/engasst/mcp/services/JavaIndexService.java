package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.ParsedFileCache;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.model.CallerInfo;
import com.engasst.mcp.model.ClassInfo;
import com.engasst.mcp.model.ImplementationInfo;
import com.engasst.mcp.model.MethodInfo;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Java AST-backed engineering queries: findClass / findMethod / findCallers /
 * findImplementations.
 *
 * Uses JavaParser with a SymbolSolver where possible; falls back to import-aware
 * heuristic matching when symbol resolution fails (common in partial workspaces
 * without resolved external dependencies).
 *
 * Source roots are discovered on demand by scanning for common Maven/Gradle
 * conventions: src/main/java, src/test/java, src/.
 */
public final class JavaIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(JavaIndexService.class);

    private final RepoWalker walker;
    private final ParsedFileCache cache;
    private final ServerConfig config;
    private final AtomicReference<Boolean> solverConfigured = new AtomicReference<>(false);

    public JavaIndexService(RepoWalker walker, ParsedFileCache cache, ServerConfig config) {
        this.walker = walker;
        this.cache = cache;
        this.config = config;
    }

    private synchronized void ensureSolverConfigured() {
        if (Boolean.TRUE.equals(solverConfigured.get())) return;
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver());
        for (Path root : discoverSourceRoots()) {
            try { ts.add(new JavaParserTypeSolver(root)); } catch (Exception ignored) {}
        }
        ParserConfiguration pc = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(ts))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        cache.configure(pc);
        solverConfigured.set(true);
        LOG.info("JavaParser symbol solver configured");
    }

    private List<Path> discoverSourceRoots() {
        List<Path> roots = new ArrayList<>();
        Path ws = config.workspaceRoot();
        Set<String> ignored = new HashSet<>(config.ignoredDirs());
        try (var stream = Files.walk(ws, 6)) {
            stream.filter(Files::isDirectory)
                    .filter(d -> {
                        // skip ignored dirs anywhere on the path
                        for (Path part : d) if (ignored.contains(part.toString())) return false;
                        String s = d.toString().replace('\\', '/');
                        return s.endsWith("/src/main/java") || s.endsWith("/src/test/java");
                    })
                    .forEach(roots::add);
        } catch (IOException ignoredEx) {}
        if (roots.isEmpty() && Files.isDirectory(ws.resolve("src"))) {
            roots.add(ws.resolve("src"));
        }
        return roots;
    }

    // --------------------------------------------------------------- findClass

    public List<ClassInfo> findClass(String name, boolean exact) throws IOException {
        Set<ClassInfo> out = new HashSet<>();
        forEachJavaFile(file -> cache.parse(file).ifPresent(cu -> {
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                String simple = td.getNameAsString();
                if (matches(simple, name, exact)) {
                    String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                    String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                    int line = td.getBegin().map(p -> p.line).orElse(-1);
                    out.add(new ClassInfo(simple, pkg, fqn, kindOf(td),
                            relativize(file), line, firstLine(td)));
                }
            }
        }));
        return List.copyOf(out);
    }

    private static String kindOf(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration c) return c.isInterface() ? "interface" : "class";
        if (td instanceof EnumDeclaration) return "enum";
        if (td instanceof RecordDeclaration) return "record";
        if (td instanceof AnnotationDeclaration) return "annotation";
        return "type";
    }

    private static String firstLine(TypeDeclaration<?> td) {
        String s = td.toString();
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    // -------------------------------------------------------------- findMethod

    public List<MethodInfo> findMethod(String methodName,
                                       String classNameFilter,
                                       boolean exact) throws IOException {
        List<MethodInfo> out = new ArrayList<>();
        forEachJavaFile(file -> cache.parse(file).ifPresent(cu -> {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (TypeDeclaration<?> td : cu.findAll(TypeDeclaration.class)) {
                String clsSimple = td.getNameAsString();
                String fqn = pkg.isEmpty() ? clsSimple : pkg + "." + clsSimple;
                if (classNameFilter != null && !classNameFilter.isBlank()
                        && !matches(clsSimple, classNameFilter, false)
                        && !matches(fqn, classNameFilter, false)) {
                    continue;
                }
                for (MethodDeclaration m : td.findAll(MethodDeclaration.class)) {
                    if (m.getBody().isEmpty()) continue;
                    if (matches(m.getNameAsString(), methodName, exact)) {
                        int line = m.getBegin().map(p -> p.line).orElse(-1);
                        out.add(new MethodInfo(
                                m.getNameAsString(),
                                m.getDeclarationAsString(true, true, true),
                                fqn,
                                relativize(file),
                                line));
                    }
                }
            }
        }));
        return out;
    }

    // -------------------------------------------------------------- findCallers

    public List<CallerInfo> findCallers(String classFqnOrSimple, String methodName) throws IOException {
        ensureSolverConfigured();
        ConcurrentLinkedQueue<CallerInfo> out = new ConcurrentLinkedQueue<>();
        String targetSimple = simpleName(classFqnOrSimple);
        boolean isFqn = classFqnOrSimple.contains(".");

        forEachJavaFile(file -> {
            CompilationUnit cu = cache.parse(file).orElse(null);
            if (cu == null) return;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(methodName)) continue;

                String enclosingClass = call.findAncestor(TypeDeclaration.class)
                        .map(t -> {
                            String n = ((NodeWithSimpleName<?>) t).getNameAsString();
                            return pkg.isEmpty() ? n : pkg + "." + n;
                        })
                        .orElse(pkg);
                String enclosingMethod = call.findAncestor(MethodDeclaration.class)
                        .map(MethodDeclaration::getNameAsString)
                        .orElseGet(() -> call.findAncestor(ConstructorDeclaration.class)
                                .map(c -> "<init>").orElse("<static>"));

                String resolution = "heuristic";
                boolean match = false;
                try {
                    var rm = call.resolve();
                    String declFqn = rm.declaringType().getQualifiedName();
                    if (isFqn ? declFqn.equals(classFqnOrSimple) : declFqn.endsWith("." + targetSimple) || declFqn.equals(targetSimple)) {
                        match = true;
                        resolution = "ast";
                    }
                } catch (RuntimeException ignored) {
                    // fall back to import-aware heuristic
                    if (heuristicMatch(cu, call, classFqnOrSimple, targetSimple, isFqn)) {
                        match = true;
                    }
                }
                if (match) {
                    int line = call.getBegin().map(p -> p.line).orElse(-1);
                    out.add(new CallerInfo(enclosingClass, enclosingMethod,
                            relativize(file), line, call.toString(), resolution));
                }
            }
        });
        return new ArrayList<>(out);
    }

    private static boolean heuristicMatch(CompilationUnit cu, MethodCallExpr call,
                                          String target, String targetSimple, boolean isFqn) {
        // If call has a scope, accept when the scope text is the target simple name
        // OR a variable typed as the target (best-effort by source-text match).
        var scope = call.getScope();
        if (scope.isPresent()) {
            String s = scope.get().toString();
            if (s.equals(targetSimple)) return true;
            if (isFqn && s.equals(target)) return true;
        } else {
            // unqualified call: must be inside the target class itself
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            return call.findAncestor(TypeDeclaration.class)
                    .map(t -> {
                        String simple = ((NodeWithSimpleName<?>) t).getNameAsString();
                        String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                        return isFqn ? fqn.equals(target) : simple.equals(targetSimple);
                    }).orElse(false);
        }
        // Check imports for FQN containing target simple name
        boolean importsTarget = cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().endsWith("." + targetSimple)
                        || i.getNameAsString().equals(target));
        return importsTarget;
    }

    // ----------------------------------------------------- findImplementations

    public List<ImplementationInfo> findImplementations(String interfaceOrClass) throws IOException {
        ensureSolverConfigured();
        String targetSimple = simpleName(interfaceOrClass);
        boolean isFqn = interfaceOrClass.contains(".");

        ConcurrentLinkedQueue<ImplementationInfo> out = new ConcurrentLinkedQueue<>();
        forEachJavaFile(file -> {
            CompilationUnit cu = cache.parse(file).orElse(null);
            if (cu == null) return;
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String simple = c.getNameAsString();
                String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                int line = c.getBegin().map(p -> p.line).orElse(-1);

                for (ClassOrInterfaceType impl : c.getImplementedTypes()) {
                    if (typeMatches(impl, interfaceOrClass, targetSimple, isFqn)) {
                        out.add(new ImplementationInfo(fqn, "implements", relativize(file), line));
                    }
                }
                for (ClassOrInterfaceType ext : c.getExtendedTypes()) {
                    if (typeMatches(ext, interfaceOrClass, targetSimple, isFqn)) {
                        out.add(new ImplementationInfo(fqn, "extends", relativize(file), line));
                    }
                }
            }
        });
        return new ArrayList<>(out);
    }

    private static boolean typeMatches(ClassOrInterfaceType t, String target, String targetSimple, boolean isFqn) {
        try {
            String fqn = t.resolve().asReferenceType().getQualifiedName();
            return isFqn ? fqn.equals(target) : fqn.endsWith("." + targetSimple) || fqn.equals(targetSimple);
        } catch (RuntimeException ignored) {
            return t.getNameAsString().equals(targetSimple);
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface JavaFileConsumer { void accept(Path p); }

    private void forEachJavaFile(JavaFileConsumer c) throws IOException {
        walker.walk(config.workspaceRoot(), walker::isJavaFile, c::accept);
    }

    private void forEachJavaFileParallel(JavaFileConsumer c) throws IOException {
        List<Path> files = walker.collect(config.workspaceRoot(), walker::isJavaFile);
        if (files.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(config.astThreadPoolSize());
        AtomicInteger remaining = new AtomicInteger(files.size());
        Object done = new Object();
        try {
            for (Path f : files) {
                pool.submit(() -> {
                    try { c.accept(f); }
                    catch (RuntimeException ignored) {}
                    finally {
                        if (remaining.decrementAndGet() == 0) {
                            synchronized (done) { done.notifyAll(); }
                        }
                    }
                });
            }
            synchronized (done) {
                while (remaining.get() > 0) {
                    try { done.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private String relativize(Path file) {
        return config.workspaceRoot().relativize(file).toString().replace('\\', '/');
    }

    private static boolean matches(String candidate, String query, boolean exact) {
        if (exact) return candidate.equals(query);
        return candidate.equalsIgnoreCase(query) || candidate.toLowerCase().contains(query.toLowerCase());
    }

    private static String simpleName(String fqnOrSimple) {
        int dot = fqnOrSimple.lastIndexOf('.');
        return dot < 0 ? fqnOrSimple : fqnOrSimple.substring(dot + 1);
    }

    /** Exposed for tests so they can trigger lazy solver init deterministically. */
    public String describeSourceRoots() {
        return discoverSourceRoots().stream().map(Path::toString).collect(Collectors.joining(", "));
    }
}
