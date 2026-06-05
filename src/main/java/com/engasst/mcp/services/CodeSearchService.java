package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.model.SearchHit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recursive text/regex search across the workspace.
 * Reads files line-by-line to keep memory bounded on multi-GB repositories.
 */
public final class CodeSearchService {

    private final RepoWalker walker;
    private final ServerConfig config;

    public CodeSearchService(RepoWalker walker, ServerConfig config) {
        this.walker = walker;
        this.config = config;
    }

    public List<SearchHit> search(String query,
                                  boolean isRegex,
                                  boolean caseSensitive,
                                  List<String> extensions,
                                  String subPath,
                                  int maxResults) throws IOException {
        if (query == null || query.isEmpty()) return List.of();
        int limit = clamp(maxResults, config.maxResults());

        Pattern pattern = isRegex
                ? Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(query), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);

        Path root = subPath == null || subPath.isBlank()
                ? config.workspaceRoot()
                : config.workspaceRoot().resolve(subPath).normalize();

        List<SearchHit> hits = new ArrayList<>();
        walker.walk(root,
                p -> matchesExtensionFilter(p, extensions) && walker.isTextFile(p),
                p -> {
                    if (hits.size() >= limit) return;
                    scanFile(p, pattern, hits, limit);
                });
        return hits;
    }

    private void scanFile(Path file, Pattern pattern, List<SearchHit> out, int limit) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            String rel = config.workspaceRoot().relativize(file).toString().replace('\\', '/');
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (pattern.matcher(line).find()) {
                    out.add(new SearchHit(rel, lineNo, truncate(line, 400)));
                    if (out.size() >= limit) return;
                }
            }
        } catch (IOException ignored) {
            // skip unreadable / non-UTF8 files
        }
    }

    private static boolean matchesExtensionFilter(Path p, List<String> exts) {
        if (exts == null || exts.isEmpty()) return true;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : exts) {
            if (name.endsWith("." + e.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static int clamp(int requested, int max) {
        if (requested <= 0) return max;
        return Math.min(requested, max);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
