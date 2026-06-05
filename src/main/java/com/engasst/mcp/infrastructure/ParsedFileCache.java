package com.engasst.mcp.infrastructure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches parsed CompilationUnits keyed by file path + last-modified time.
 * Re-parses on mtime change. Designed to be the single point of JavaParser
 * use so we don't pay the parse cost repeatedly across tool invocations.
 *
 * <p>Non-final to allow test-only spies that observe {@link #parse} entry
 * ordering relative to other pipeline events. Do not subclass in production
 * code.
 */
public class ParsedFileCache {

    private record Entry(long mtime, CompilationUnit cu) {}

    private final ConcurrentHashMap<Path, Entry> cache = new ConcurrentHashMap<>();
    private volatile JavaParser parser;

    public void configure(ParserConfiguration cfg) {
        this.parser = new JavaParser(cfg);
    }

    public JavaParser parser() {
        JavaParser p = parser;
        if (p == null) {
            synchronized (this) {
                if (parser == null) parser = new JavaParser(new ParserConfiguration());
                p = parser;
            }
        }
        return p;
    }

    public Optional<CompilationUnit> parse(Path file) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Entry e = cache.get(file);
            if (e != null && e.mtime == mtime) return Optional.of(e.cu);
            ParseResult<CompilationUnit> res = parser().parse(file);
            if (res.isSuccessful() && res.getResult().isPresent()) {
                CompilationUnit cu = res.getResult().get();
                cache.put(file, new Entry(mtime, cu));
                return Optional.of(cu);
            }
            return Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public void clear() { cache.clear(); }
    public int size()   { return cache.size(); }
}
