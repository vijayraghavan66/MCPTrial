package com.engasst.mcp.debug.score;

import com.engasst.mcp.debug.git.GitAnalyzer.FileChange;
import com.engasst.mcp.debug.model.StackFrame;

/**
 * Deterministic scoring for the debug engine. Every factor returns a value
 * in [0..100] so callers can combine them with explicit weights without
 * worrying about normalisation.
 */
public final class ScoringEngine {

    /** Weights (sum to 1.0) used by {@link #combine(double, double, double, double, double)}. */
    public static final double W_FRAME_DISTANCE     = 0.25;
    public static final double W_COMMIT_RECENCY     = 0.20;
    public static final double W_COMMIT_FREQUENCY   = 0.10;
    public static final double W_DEPENDENCY_PROX    = 0.25;
    public static final double W_HISTORICAL_FAIL    = 0.20;

    /** 100 for the topmost frame, decays geometrically with index. */
    public double frameDistance(int frameIndex) {
        if (frameIndex < 0) return 0;
        return Math.max(0, 100.0 * Math.pow(0.75, frameIndex));
    }

    /** 100 if a commit was made today, 0 when older than 180 days. Linear in-between. */
    public double commitRecency(long daysSinceLast) {
        if (daysSinceLast < 0 || daysSinceLast == Long.MAX_VALUE) return 0;
        if (daysSinceLast >= 180) return 0;
        return 100.0 * (1.0 - (daysSinceLast / 180.0));
    }

    /** Saturates around 20+ commits per 90 days. log-scaled to avoid blowups on hot files. */
    public double commitFrequency(int commitsLast90Days) {
        if (commitsLast90Days <= 0) return 0;
        double v = Math.log1p(commitsLast90Days) / Math.log1p(20.0);
        return Math.min(100.0, v * 100.0);
    }

    /**
     * 100 if class is on the stack itself (hopDistance == 0),
     * decays with dependency-path length. 0 if unreachable.
     */
    public double dependencyProximity(int hopDistance) {
        if (hopDistance < 0) return 0;
        if (hopDistance == 0) return 100;
        return Math.max(0, 100.0 - hopDistance * 20.0);
    }

    /**
     * Maps prior occurrences of this exact (exception, top-method) signature
     * to a saturating 0..100 score. 0 occurrences => 0, plateau at ~10.
     */
    public double historicalFailure(int priorOccurrences) {
        if (priorOccurrences <= 0) return 0;
        double v = Math.log1p(priorOccurrences) / Math.log1p(10.0);
        return Math.min(100.0, v * 100.0);
    }

    /** Convenience for the standard 5-factor weighted sum. */
    public double combine(double frame, double recency, double frequency,
                          double depProx, double historical) {
        return clamp(frame * W_FRAME_DISTANCE
                   + recency * W_COMMIT_RECENCY
                   + frequency * W_COMMIT_FREQUENCY
                   + depProx * W_DEPENDENCY_PROX
                   + historical * W_HISTORICAL_FAIL);
    }

    /** Score a stack frame as a "relevant method" finding. */
    public double scoreFrame(StackFrame f) {
        double base = frameDistance(f.index);
        if (!f.inRepo) base *= 0.2; // external frames still listed but down-ranked
        return base;
    }

    /** Score a "recent change" finding from a file's commit history. */
    public double scoreFileChange(FileChange ch, double frameProximity) {
        return combine(frameProximity,
                       commitRecency(ch.daysSinceLast()),
                       commitFrequency(ch.commitsLast90Days()),
                       frameProximity, 0);
    }

    /** Mean of finding scores, normalised to [0,1] for the report header. */
    public double overallConfidence(double[] topFindings) {
        if (topFindings == null || topFindings.length == 0) return 0;
        double sum = 0;
        for (double v : topFindings) sum += v;
        return clamp(sum / topFindings.length) / 100.0;
    }

    private static double clamp(double v) { return Math.max(0, Math.min(100, v)); }
}
