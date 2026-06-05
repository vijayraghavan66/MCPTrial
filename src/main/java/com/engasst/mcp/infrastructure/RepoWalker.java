package com.engasst.mcp.infrastructure;

import com.engasst.mcp.config.ServerConfig;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Walks the workspace honouring ignore rules and size limits.
 * Designed for repositories with millions of files: never materializes the
 * full file list when a consumer is supplied; supports early termination.
 */
public final class RepoWalker {

    private final ServerConfig config;
    private final Set<String> ignoredDirs;

    public RepoWalker(ServerConfig config) {
        this.config = config;
        this.ignoredDirs = new HashSet<>(config.ignoredDirs());
    }

    /** Walk all files matching {@code filter}, invoking {@code sink} for each. */
    public void walk(Path root, Predicate<Path> filter, Consumer<Path> sink) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && ignoredDirs.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > config.maxFileSizeBytes()) return FileVisitResult.CONTINUE;
                if (filter.test(file)) sink.accept(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Convenience: collect all matching files (use only when you need a list). */
    public List<Path> collect(Path root, Predicate<Path> filter) throws IOException {
        List<Path> out = new ArrayList<>();
        walk(root, filter, out::add);
        return out;
    }

    public boolean isTextFile(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1).toLowerCase();
        return config.textFileExtensions().contains(ext);
    }

    public boolean isJavaFile(Path p) {
        return p.getFileName().toString().endsWith(".java");
    }

    public ServerConfig config() { return config; }
}
