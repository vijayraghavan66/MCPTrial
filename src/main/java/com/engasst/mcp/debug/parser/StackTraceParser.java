package com.engasst.mcp.debug.parser;

import com.engasst.mcp.debug.model.ParsedStackTrace;
import com.engasst.mcp.debug.model.ParsedStackTrace.ExceptionLink;
import com.engasst.mcp.debug.model.StackFrame;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Java stack traces and exception messages out of arbitrary text
 * (log file fragments, test output, raw stack dumps). Pure / deterministic.
 */
public final class StackTraceParser {

    // "at com.acme.svc.OrderService.submit(OrderService.java:42)"
    // "at com.acme.svc.OrderService.submit(OrderService.java)"
    // "at com.acme.svc.OrderService.submit(Native Method)"
    // "at com.acme.svc.OrderService.submit(Unknown Source)"
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+([\\w$.<>]+)\\.([\\w$<>]+)\\s*\\(([^)]*)\\)\\s*$");

    // First line:  "java.lang.NoSuchMethodError: org.bouncycastle...someMethod()"
    // Caused by:   "Caused by: java.lang.NullPointerException: foo"
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Caused by:\\s+|Suppressed:\\s+)?([\\w$.]+(?:Exception|Error|Throwable))(?::\\s*(.*))?$");

    private static final Pattern FILE_LINE = Pattern.compile("(.+?):(\\d+)$");

    public ParsedStackTrace parse(String text) {
        ParsedStackTrace out = new ParsedStackTrace(text == null ? "" : text);
        if (text == null || text.isBlank()) return out;

        String[] lines = text.split("\\r?\\n");
        int frameIdx = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher fm = FRAME.matcher(line);
            if (fm.matches()) {
                String classFqn = fm.group(1);
                String method   = fm.group(2);
                String src      = fm.group(3);
                String fileHint = null;
                Integer ln = null;
                Matcher flm = FILE_LINE.matcher(src);
                if (flm.matches()) {
                    fileHint = flm.group(1);
                    try { ln = Integer.parseInt(flm.group(2)); }
                    catch (NumberFormatException ignored) {}
                } else if (!src.equals("Native Method") && !src.equals("Unknown Source")) {
                    fileHint = src;
                }
                out.frames.add(new StackFrame(frameIdx++, classFqn, method, fileHint, ln));
                continue;
            }

            // Skip "... 12 more" lines silently.
            if (trimmed.matches("\\.{3}\\s*\\d+\\s+more")) continue;

            Matcher em = EXCEPTION.matcher(trimmed);
            if (em.matches()) {
                out.chain.add(new ExceptionLink(em.group(1), em.group(2)));
            }
        }
        return out;
    }

    /**
     * Extract just the exception class name from a raw error message like
     * "java.lang.NoSuchMethodError: org.bouncycastle..." — convenience for
     * the {@code investigateException} flow.
     */
    public String extractExceptionFqn(String message) {
        if (message == null) return null;
        Matcher m = EXCEPTION.matcher(message.trim());
        return m.matches() ? m.group(1) : null;
    }
}
