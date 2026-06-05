package com.engasst.mcp.services;

import com.engasst.mcp.config.ServerConfig;
import com.engasst.mcp.infrastructure.RepoWalker;
import com.engasst.mcp.model.LogHit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogSearchService {

    /** Matches the leading timestamp of common log formats (ISO-8601 or "yyyy-MM-dd HH:mm:ss[.SSS]"). */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\s*(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)");

    private final RepoWalker walker;
    private final ServerConfig config;

    public LogSearchService(RepoWalker walker, ServerConfig config) {
        this.walker = walker;
        this.config = config;
    }

    public List<LogHit> search(String pattern,
                               boolean isRegex,
                               boolean caseSensitive,
                               String subPath,
                               int maxResults) throws IOException {
        if (pattern == null || pattern.isEmpty()) return List.of();
        int limit = maxResults <= 0 ? config.maxResults() : Math.min(maxResults, config.maxResults());
        Pattern p = isRegex
                ? Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE)
                : Pattern.compile(Pattern.quote(pattern), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);

        Path root = (subPath == null || subPath.isBlank())
                ? config.workspaceRoot()
                : config.workspaceRoot().resolve(subPath).normalize();

        List<LogHit> hits = new ArrayList<>();
        walker.walk(root, this::isLogFile, file -> {
            if (hits.size() >= limit) return;
            scan(file, p, hits, limit);
        });
        return hits;
    }

    private boolean isLogFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".log") || n.endsWith(".out") || n.endsWith(".txt") || n.contains(".log.");
    }

    private void scan(Path file, Pattern pattern, List<LogHit> out, int limit) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            String rel = config.workspaceRoot().relativize(file).toString().replace('\\', '/');
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (pattern.matcher(line).find()) {
                    String ts = null;
                    Matcher m = TIMESTAMP.matcher(line);
                    if (m.find()) ts = m.group(1);
                    out.add(new LogHit(rel, lineNo, ts, truncate(line, 600)));
                    if (out.size() >= limit) return;
                }
            }
        } catch (IOException ignored) {}
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
