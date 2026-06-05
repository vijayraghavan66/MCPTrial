package com.engasst.mcp.debug;

import com.engasst.mcp.debug.correlate.PathFinder;
import com.engasst.mcp.debug.correlate.SymbolLocator;
import com.engasst.mcp.debug.git.GitAnalyzer;
import com.engasst.mcp.debug.git.GitAnalyzer.FileChange;
import com.engasst.mcp.debug.model.Finding;
import com.engasst.mcp.debug.model.InvestigationReport;
import com.engasst.mcp.debug.model.ParsedStackTrace;
import com.engasst.mcp.debug.model.RootCause;
import com.engasst.mcp.debug.model.StackFrame;
import com.engasst.mcp.debug.parser.StackTraceParser;
import com.engasst.mcp.debug.rootcause.RootCauseEngine;
import com.engasst.mcp.debug.score.ScoringEngine;
import com.engasst.mcp.debug.store.FailureHistoryStore;
import com.engasst.mcp.intel.IntelEngine;
import com.engasst.mcp.intel.query.TraceService.FlowNode;
import com.engasst.mcp.model.CommitInfo;
import com.engasst.mcp.services.GitService;
import com.engasst.mcp.services.LogSearchService;
import com.engasst.mcp.model.LogHit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Facade for the Debug Intelligence Engine. Orchestrates parser →
 * intel-engine correlation → git analysis → root-cause engine → scoring.
 *
 * <p>Every public method below corresponds 1:1 to the operations requested
 * in the design brief.
 */
public final class DebugEngine {

    private final IntelEngine intel;
    private final GitService git;
    private final LogSearchService logs;

    private final StackTraceParser parser  = new StackTraceParser();
    private final RootCauseEngine  rcEngine = new RootCauseEngine();
    private final ScoringEngine    scoring = new ScoringEngine();
    private final SymbolLocator    locator;
    private final PathFinder       paths;
    private final GitAnalyzer      gitAnalyzer;
    private final FailureHistoryStore history;

    public DebugEngine(IntelEngine intel, GitService git, LogSearchService logs) {
        this.intel = intel;
        this.git   = git;
        this.logs  = logs;
        this.locator     = new SymbolLocator(intel.store());
        this.paths       = new PathFinder(intel.trace());
        this.gitAnalyzer = new GitAnalyzer(git);
        this.history     = new FailureHistoryStore(intel.store());
    }

    // ============================================================== parsing

    public ParsedStackTrace parseStackTrace(String text) {
        return parser.parse(text);
    }

    // ============================================================ relevance

    /** Frames mapped to repo symbols, ranked by frame distance + repo presence. */
    public List<Finding> findRelevantMethods(ParsedStackTrace stack) throws SQLException {
        locator.locate(stack.frames);
        List<Finding> out = new ArrayList<>();
        for (StackFrame f : stack.frames) {
            double s = scoring.scoreFrame(f);
            Finding.Kind k = f.inRepo ? Finding.Kind.RELEVANT_METHOD : Finding.Kind.EXTERNAL_FRAME;
            out.add(new Finding(k, f.displaySignature(), s)
                    .with("frameIndex", f.index)
                    .with("classFqn",   f.classFqn)
                    .with("method",     f.method)
                    .with("line",       f.lineNumber)
                    .with("inRepo",     f.inRepo)
                    .with("filePath",   f.filePath)
                    .with("symbolId",   f.symbolId)
                    .withScore("frameDistance", scoring.frameDistance(f.index)));
        }
        out.sort(Comparator.comparingDouble((Finding x) -> x.score).reversed());
        return out;
    }

    /** Execution flows downstream of each in-repo frame. */
    public List<Finding> findRelevantExecutionPaths(ParsedStackTrace stack, int maxDepth) throws SQLException {
        locator.locate(stack.frames);
        List<Finding> out = new ArrayList<>();
        for (StackFrame f : stack.frames) {
            if (!f.inRepo) continue;
            List<FlowNode> nodes = paths.flowFrom(f, maxDepth);
            if (nodes.isEmpty()) continue;
            double frameProx = scoring.frameDistance(f.index);
            double score = scoring.combine(frameProx, 0, 0, 100, 0);
            out.add(new Finding(Finding.Kind.EXECUTION_PATH,
                        f.classFqn + "#" + f.method + " (downstream)", score)
                    .with("from", f.classFqn + "#" + f.method)
                    .with("nodeCount", nodes.size())
                    .with("nodes", flowToCompact(nodes))
                    .withScore("frameDistance", frameProx)
                    .withScore("dependencyProximity", 100));
        }
        out.sort(Comparator.comparingDouble((Finding x) -> x.score).reversed());
        return out;
    }

    // ================================================================ git

    /**
     * Recent commits touching files that appear in the stack OR are reached
     * by tracing from any in-repo frame.
     */
    public List<Finding> findRecentChanges(ParsedStackTrace stack, int reachDepth) throws SQLException {
        if (!gitAnalyzer.gitAvailable()) return List.of();
        locator.locate(stack.frames);
        Set<String> paths = collectRelevantFiles(stack, reachDepth);

        Map<String, FileChange> changes = gitAnalyzer.forFiles(paths);
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, FileChange> e : changes.entrySet()) {
            FileChange ch = e.getValue();
            double frameProx = frameProximityForFile(stack, e.getKey());
            double score = scoring.scoreFileChange(ch, frameProx);
            out.add(new Finding(Finding.Kind.RECENT_CHANGE, e.getKey(), score)
                    .with("path", e.getKey())
                    .with("commitsLast90Days", ch.commitsLast90Days())
                    .with("daysSinceLast", ch.daysSinceLast())
                    .with("commits", commitsCompact(ch.commits(), 5))
                    .withScore("commitRecency",   scoring.commitRecency(ch.daysSinceLast()))
                    .withScore("commitFrequency", scoring.commitFrequency(ch.commitsLast90Days()))
                    .withScore("frameDistance",   frameProx)
                    .withScore("dependencyProximity", frameProx));
        }
        out.sort(Comparator.comparingDouble((Finding x) -> x.score).reversed());
        return out;
    }

    /**
     * Files that (a) changed recently AND (b) are reachable from the failing
     * stack — strongest regression candidates.
     */
    public List<Finding> findRegressionCandidates(ParsedStackTrace stack, int reachDepth) throws SQLException {
        List<Finding> changes = findRecentChanges(stack, reachDepth);
        List<Finding> out = new ArrayList<>();
        for (Finding ch : changes) {
            int days = ((Number) ch.data.getOrDefault("daysSinceLast", Long.MAX_VALUE)).intValue();
            if (days > 30) continue;
            double score = Math.min(100.0, ch.score + 10.0);
            Finding cand = new Finding(Finding.Kind.REGRESSION_CANDIDATE,
                    ch.data.get("path") + " (changed " + days + "d ago)", score);
            cand.data.putAll(ch.data);
            cand.scoreBreakdown.putAll(ch.scoreBreakdown);
            cand.withScore("regressionBoost", 10);
            out.add(cand);
        }
        out.sort(Comparator.comparingDouble((Finding x) -> x.score).reversed());
        return out;
    }

    // ========================================================= root cause

    public List<RootCause> generateRootCauseAnalysis(ParsedStackTrace stack) {
        return rcEngine.classify(stack);
    }

    // =========================================================== reports

    public InvestigationReport analyzeStackTrace(String text) throws SQLException {
        ParsedStackTrace stack = parseStackTrace(text);
        InvestigationReport rep = new InvestigationReport("STACK_TRACE", briefInput(text));
        rep.parsedStack = stack;

        List<Finding> methods   = findRelevantMethods(stack);
        List<Finding> execPaths = findRelevantExecutionPaths(stack, 5);
        List<Finding> recent    = findRecentChanges(stack, 4);
        List<Finding> regress   = findRegressionCandidates(stack, 4);
        List<Finding> hist      = historicalCorrelation(stack);

        rep.findings.addAll(methods);
        rep.findings.addAll(execPaths);
        rep.findings.addAll(recent);
        rep.findings.addAll(regress);
        rep.findings.addAll(hist);
        rep.findings.sort(Comparator.comparingDouble((Finding f) -> f.score).reversed());

        rep.rootCauses.addAll(generateRootCauseAnalysis(stack));
        rep.stats.put("frames", stack.frames.size());
        rep.stats.put("inRepoFrames", paths.inRepoFrames(stack.frames).size());
        rep.stats.put("findings", rep.findings.size());
        rep.overallConfidence = computeOverallConfidence(rep);

        recordHistory(stack, "stack");
        return rep;
    }

    public InvestigationReport analyzeLogFile(String logFilePath, int maxStacks) throws Exception {
        Path file = intel.workspaceRoot().resolve(logFilePath).normalize();
        if (!Files.isRegularFile(file)) {
            InvestigationReport empty = new InvestigationReport("LOG_FILE", logFilePath);
            empty.stats.put("error", "Log file not found: " + logFilePath);
            return empty;
        }
        String text = Files.readString(file);
        List<String> chunks = splitStackChunks(text, maxStacks <= 0 ? 5 : maxStacks);

        InvestigationReport rep = new InvestigationReport("LOG_FILE", logFilePath);
        rep.stats.put("stackTracesFound", chunks.size());
        for (String chunk : chunks) {
            InvestigationReport sub = analyzeStackTrace(chunk);
            rep.findings.addAll(sub.findings);
            rep.rootCauses.addAll(sub.rootCauses);
            if (rep.parsedStack == null) rep.parsedStack = sub.parsedStack;
        }
        rep.findings.sort(Comparator.comparingDouble((Finding f) -> f.score).reversed());
        rep.overallConfidence = computeOverallConfidence(rep);
        return rep;
    }

    public InvestigationReport investigateException(String exceptionClass) throws Exception {
        InvestigationReport rep = new InvestigationReport("EXCEPTION", exceptionClass);
        if (exceptionClass == null || exceptionClass.isBlank()) {
            rep.stats.put("error", "exceptionClass is required"); return rep;
        }
        String simple = exceptionClass.substring(exceptionClass.lastIndexOf('.') + 1);

        // Synthesise a minimal parsed stack so the root-cause rule fires.
        ParsedStackTrace synthetic = new ParsedStackTrace(exceptionClass);
        synthetic.chain.add(new ParsedStackTrace.ExceptionLink(exceptionClass, null));
        rep.parsedStack = synthetic;
        rep.rootCauses.addAll(rcEngine.classify(synthetic));

        // Search logs for prior occurrences.
        try {
            List<LogHit> hits = logs.search(simple, false, false, null, 50);
            rep.stats.put("logOccurrences", hits.size());
            for (int i = 0; i < Math.min(hits.size(), 20); i++) {
                LogHit h = hits.get(i);
                rep.findings.add(new Finding(Finding.Kind.HISTORICAL_FAILURE,
                        h.file() + ":" + h.line(), 60)
                        .with("file", h.file()).with("line", h.line())
                        .with("timestamp", h.timestamp()).with("text", h.entry()));
            }
        } catch (IOException ignored) {}

        int historical = history.priorByException(exceptionClass);
        rep.stats.put("priorOccurrences", historical);
        if (historical > 0) {
            rep.findings.add(new Finding(Finding.Kind.HISTORICAL_FAILURE,
                    "Seen " + historical + " time(s) before",
                    scoring.historicalFailure(historical))
                    .with("exception", exceptionClass)
                    .withScore("historicalFailure", scoring.historicalFailure(historical)));
        }
        rep.findings.sort(Comparator.comparingDouble((Finding f) -> f.score).reversed());
        rep.overallConfidence = computeOverallConfidence(rep);
        return rep;
    }

    public InvestigationReport investigateFailure(String classFqn, String method, int maxDepth) throws SQLException {
        InvestigationReport rep = new InvestigationReport(
                "METHOD", classFqn + "#" + method);
        Long id = locator.resolve(classFqn, method);
        if (id == null) {
            rep.stats.put("error", "Method not found in index. Run buildIndex first.");
            return rep;
        }
        // Synthetic single-frame stack so the rest of the pipeline applies.
        ParsedStackTrace synth = new ParsedStackTrace(classFqn + "#" + method);
        StackFrame frame = new StackFrame(0, classFqn, method, null, null);
        frame.symbolId = id; frame.inRepo = true;
        frame.filePath = locator.fileForClass(classFqn);
        synth.frames.add(frame);
        rep.parsedStack = synth;

        rep.findings.addAll(findRelevantExecutionPaths(synth, maxDepth));
        rep.findings.addAll(findRecentChanges(synth, maxDepth));
        rep.findings.addAll(findRegressionCandidates(synth, maxDepth));
        rep.findings.sort(Comparator.comparingDouble((Finding f) -> f.score).reversed());
        rep.overallConfidence = computeOverallConfidence(rep);
        return rep;
    }

    public InvestigationReport generateInvestigationReport(String stackOrEmpty) throws SQLException {
        if (stackOrEmpty != null && !stackOrEmpty.isBlank()) return analyzeStackTrace(stackOrEmpty);
        // No input given — synthesise a "recent failures" summary from history.
        InvestigationReport rep = new InvestigationReport("HISTORY", "recent failure history");
        // (Cheap query — bounded list of recent rows.)
        try (var c = intel.store().db().readConnection();
             var ps = c.prepareStatement(
                "SELECT exception_fqn, top_class_fqn, top_method, occurred_at " +
                "FROM failure_history ORDER BY occurred_at DESC LIMIT 50")) {
            try (var rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) {
                    n++;
                    rep.findings.add(new Finding(Finding.Kind.HISTORICAL_FAILURE,
                            rs.getString(1) + " in " + rs.getString(2) + "#" + rs.getString(3),
                            60)
                            .with("exception", rs.getString(1))
                            .with("class", rs.getString(2))
                            .with("method", rs.getString(3))
                            .with("occurredAt", rs.getLong(4)));
                }
                rep.stats.put("entries", n);
            }
        } catch (SQLException ex) {
            rep.stats.put("error", ex.getMessage());
        }
        return rep;
    }

    // ============================================================ helpers

    private List<Finding> historicalCorrelation(ParsedStackTrace stack) throws SQLException {
        if (stack.primary() == null) return List.of();
        StackFrame top = stack.frames.isEmpty() ? null : stack.frames.get(0);
        String sig = FailureHistoryStore.signature(
                stack.primary().exceptionFqn,
                top == null ? null : top.classFqn,
                top == null ? null : top.method);
        int prior = history.priorOccurrences(sig);
        if (prior == 0) return List.of();
        double s = scoring.historicalFailure(prior);
        return List.of(new Finding(Finding.Kind.HISTORICAL_FAILURE,
                "Identical failure seen " + prior + " time(s) before", s)
                .with("signature", sig)
                .with("priorOccurrences", prior)
                .withScore("historicalFailure", s));
    }

    private void recordHistory(ParsedStackTrace stack, String source) {
        if (stack.primary() == null) return;
        StackFrame top = stack.frames.isEmpty() ? null : stack.frames.get(0);
        String sig = FailureHistoryStore.signature(
                stack.primary().exceptionFqn,
                top == null ? null : top.classFqn,
                top == null ? null : top.method);
        try {
            Long repoId = intel.store().repoIdByPath(intel.workspaceRoot().toString()).orElse(null);
            history.record(repoId, sig, stack.primary().exceptionFqn,
                    top == null ? null : top.classFqn,
                    top == null ? null : top.method,
                    top == null ? null : top.filePath, source);
        } catch (SQLException ignored) {}
    }

    /** Pull every file referenced by stack frames + every file reached transitively. */
    private Set<String> collectRelevantFiles(ParsedStackTrace stack, int reachDepth) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        for (StackFrame f : stack.frames) {
            if (f.filePath != null) out.add(f.filePath);
            if (f.inRepo && f.filePath == null) {
                String p = locator.fileForClass(f.classFqn);
                if (p != null) { f.filePath = p; out.add(p); }
            }
        }
        Set<String> reachable = paths.reachableClasses(stack.frames, reachDepth);
        for (String fqn : reachable) {
            String p = locator.fileForClass(fqn);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * Approximate frame-distance proximity for a given file: 100 if the
     * file is the top frame's file, decays with frame index, 40 if reached
     * only via traversal.
     */
    private double frameProximityForFile(ParsedStackTrace stack, String file) {
        for (StackFrame f : stack.frames) {
            if (file.equals(f.filePath)) return scoring.frameDistance(f.index);
        }
        return 40.0;
    }

    private double computeOverallConfidence(InvestigationReport rep) {
        int top = Math.min(5, rep.findings.size());
        double[] arr = new double[top];
        for (int i = 0; i < top; i++) arr[i] = rep.findings.get(i).score;
        double findingMean = scoring.overallConfidence(arr);
        double rcConf = rep.rootCauses.isEmpty() ? 0 : rep.rootCauses.get(0).confidence;
        // Weighted mean: findings 0.6, root cause 0.4
        return Math.max(0, Math.min(1, findingMean * 0.6 + rcConf * 0.4));
    }

    private List<Map<String, Object>> flowToCompact(List<FlowNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>(nodes.size());
        for (FlowNode n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class",  n.classFqn());
            m.put("method", n.method());
            m.put("role",   n.role());
            m.put("file",   n.file());
            m.put("line",   n.line());
            m.put("depth",  n.depth());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> commitsCompact(List<CommitInfo> commits, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, commits.size()); i++) {
            CommitInfo c = commits.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha",     c.shortHash());
            m.put("author",  c.author());
            m.put("date",    c.date());
            m.put("message", c.message().split("\\r?\\n", 2)[0]);
            out.add(m);
        }
        return out;
    }

    private static String briefInput(String text) {
        if (text == null) return "";
        String first = text.split("\\r?\\n", 2)[0].trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    /**
     * Greedy splitter: a "stack chunk" starts at an exception line and runs
     * until the next blank-line cluster or the next exception line.
     */
    private static List<String> splitStackChunks(String text, int max) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        StringBuilder buf = null;
        for (String line : lines) {
            boolean isExc = line.matches("^(?:Caused by:\\s+|Suppressed:\\s+)?[\\w$.]+(?:Exception|Error|Throwable)(?::.*)?$");
            boolean isFrame = line.matches("^\\s*at\\s+[\\w$.<>]+\\.[\\w$<>]+\\([^)]*\\)\\s*$");
            if (isExc) {
                if (buf != null && buf.length() > 0) { out.add(buf.toString()); if (out.size() >= max) return out; }
                buf = new StringBuilder().append(line).append('\n');
            } else if (buf != null && (isFrame || line.trim().startsWith("...") || line.trim().startsWith("Caused by"))) {
                buf.append(line).append('\n');
            } else if (buf != null && line.isBlank()) {
                out.add(buf.toString()); buf = null;
                if (out.size() >= max) return out;
            }
        }
        if (buf != null && buf.length() > 0) out.add(buf.toString());
        return out;
    }

    // ============================================================== accessors
    public StackTraceParser parser()        { return parser; }
    public RootCauseEngine  rootCauseEngine() { return rcEngine; }
    public ScoringEngine    scoring()       { return scoring; }
    public SymbolLocator    locator()       { return locator; }
    public PathFinder       paths()         { return paths; }
    public GitAnalyzer      gitAnalyzer()   { return gitAnalyzer; }
    public FailureHistoryStore history()    { return history; }

    /** Bridge for callers that want the report rendered as a portable map. */
    public Map<String, Object> toMap(InvestigationReport rep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inputKind", rep.inputKind);
        m.put("input", rep.input);
        m.put("generatedAt", rep.generatedAt);
        m.put("overallConfidence", rep.overallConfidence);
        if (rep.parsedStack != null) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("exceptionChain", rep.parsedStack.chain.stream().map(l -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("exception", l.exceptionFqn); o.put("message", l.message); return o;
            }).toList());
            ps.put("frames", rep.parsedStack.frames.stream().map(f -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("index", f.index); o.put("class", f.classFqn); o.put("method", f.method);
                o.put("file", f.fileHint); o.put("line", f.lineNumber);
                o.put("inRepo", f.inRepo); o.put("symbolId", f.symbolId); o.put("filePath", f.filePath);
                return o;
            }).toList());
            m.put("parsedStack", ps);
        }
        List<Map<String, Object>> rcs = new ArrayList<>();
        for (RootCause rc : rep.rootCauses) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("category", rc.category); o.put("summary", rc.summary);
            o.put("confidence", rc.confidence);
            o.put("suggestedSteps", rc.suggestedSteps); o.put("evidence", rc.evidence);
            rcs.add(o);
        }
        m.put("rootCauses", rcs);
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Finding f : rep.findings) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("kind", f.kind.name()); o.put("title", f.title); o.put("score", f.score);
            o.put("data", f.data); o.put("scoreBreakdown", f.scoreBreakdown);
            fs.add(o);
        }
        m.put("findings", fs);
        m.put("stats", rep.stats);
        return m;
    }

    /** Allow callers to suppress the "use this" hint. */
    @SuppressWarnings("unused") private Optional<Void> _unused() { return Optional.empty(); }
}
