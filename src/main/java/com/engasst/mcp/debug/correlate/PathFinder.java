package com.engasst.mcp.debug.correlate;

import com.engasst.mcp.debug.model.StackFrame;
import com.engasst.mcp.intel.query.TraceService;
import com.engasst.mcp.intel.query.TraceService.DependencyHop;
import com.engasst.mcp.intel.query.TraceService.FlowNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Combines the existing graph queries into the shapes the debug engine
 * needs:
 *   - the downstream execution flow from each in-repo frame,
 *   - the dependency path between adjacent frames in the stack.
 */
public final class PathFinder {

    private final TraceService trace;
    private final int defaultFlowDepth;
    private final int defaultDepDepth;

    public PathFinder(TraceService trace) {
        this(trace, 5, 6);
    }

    public PathFinder(TraceService trace, int defaultFlowDepth, int defaultDepDepth) {
        this.trace = trace;
        this.defaultFlowDepth = defaultFlowDepth;
        this.defaultDepDepth  = defaultDepDepth;
    }

    /** Execution tree downstream of a frame, capped at {@code maxDepth}. */
    public List<FlowNode> flowFrom(StackFrame f, int maxDepth) throws SQLException {
        if (!f.inRepo) return List.of();
        return trace.traceExecutionFlow(f.classFqn, f.method, maxDepth > 0 ? maxDepth : defaultFlowDepth);
    }

    /** Dependency path between two adjacent in-repo frames (caller -> callee). */
    public List<DependencyHop> betweenFrames(StackFrame caller, StackFrame callee, int maxDepth) throws SQLException {
        if (caller == null || callee == null) return List.of();
        if (caller.classFqn.equals(callee.classFqn)) return List.of();
        return trace.findDependencyPath(caller.classFqn, callee.classFqn,
                maxDepth > 0 ? maxDepth : defaultDepDepth);
    }

    /**
     * Collect the set of (classFqn) reached when tracing from every in-repo
     * frame in the stack. Used to score "is this changed class relevant?".
     */
    public Set<String> reachableClasses(List<StackFrame> frames, int maxDepth) throws SQLException {
        Set<String> out = new HashSet<>();
        for (StackFrame f : frames) {
            if (!f.inRepo) continue;
            out.add(f.classFqn);
            for (FlowNode n : flowFrom(f, maxDepth)) {
                if (n.classFqn() != null) out.add(n.classFqn());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Convenience for callers that already have an integer for depth. */
    public List<FlowNode> flowFromMethod(String classFqn, String method, int maxDepth) throws SQLException {
        return trace.traceExecutionFlow(classFqn, method, maxDepth > 0 ? maxDepth : defaultFlowDepth);
    }

    public List<StackFrame> inRepoFrames(List<StackFrame> frames) {
        List<StackFrame> out = new ArrayList<>();
        for (StackFrame f : frames) if (f.inRepo) out.add(f);
        return out;
    }
}
