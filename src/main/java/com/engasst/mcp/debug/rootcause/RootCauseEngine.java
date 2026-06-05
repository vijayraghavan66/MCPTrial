package com.engasst.mcp.debug.rootcause;

import com.engasst.mcp.debug.model.ParsedStackTrace;
import com.engasst.mcp.debug.model.ParsedStackTrace.ExceptionLink;
import com.engasst.mcp.debug.model.RootCause;
import com.engasst.mcp.debug.model.StackFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic root-cause classifier. Looks at the exception chain and
 * frame topology to suggest the most likely category + investigation
 * steps. No LLM; just rules. Multiple hypotheses can be returned, ranked
 * by confidence.
 */
public final class RootCauseEngine {

    /** Rule table: exception FQN suffix -> (category, summary template, steps). */
    private static final Map<String, Rule> RULES = Map.ofEntries(
        Map.entry("NoSuchMethodError", new Rule(
            "CLASSPATH_MISMATCH",
            "A method present at compile time is missing at runtime — almost always a library/version mismatch.",
            List.of(
                "Check recent pom.xml / build.gradle commits for version bumps of the offending package.",
                "Run `mvn dependency:tree` and verify the version of the artefact owning the missing class.",
                "Look for duplicate jars on the classpath or shaded/relocated variants overriding the expected one.",
                "Confirm the deployed artefact's manifest matches what was built."))),
        Map.entry("NoClassDefFoundError", new Rule(
            "CLASSPATH_MISMATCH",
            "Class present at compile time was missing or failed static init at runtime.",
            List.of(
                "Inspect the 'Caused by' chain — an ExceptionInInitializerError points to a static block failure.",
                "Check that the artefact owning the class is on the runtime classpath.",
                "Audit recent build / deployment commits for module exclusions."))),
        Map.entry("ClassNotFoundException", new Rule(
            "CLASSPATH_MISMATCH",
            "Reflective class load failed — dependency missing or wrong classloader.",
            List.of(
                "Confirm the class name is spelled and packaged exactly as referenced.",
                "If running under a container, verify the correct module / plugin classloader is used.",
                "Inspect dependency tree for an evicted version."))),
        Map.entry("NullPointerException", new Rule(
            "NULL_DEREFERENCE",
            "An object was dereferenced before being initialised on this path.",
            List.of(
                "Inspect the top frame for fields/locals that may be null on this branch.",
                "Examine recent changes to the top method for newly added field reads.",
                "Check whether any upstream caller can pass a null argument."))),
        Map.entry("StackOverflowError", new Rule(
            "INFINITE_RECURSION",
            "Recursion or mutual recursion blew the stack on this path.",
            List.of(
                "Inspect the top ~5 frames for repetition — that is the recursion cycle.",
                "Verify the recursion base case is reached for the failing input.",
                "Consider whether equals/hashCode/toString accidentally call each other."))),
        Map.entry("OutOfMemoryError", new Rule(
            "MEMORY_PRESSURE",
            "JVM heap or metaspace exhausted.",
            List.of(
                "Look for unbounded collections or caches in the top frames' classes.",
                "Examine recent code changes that increased per-request allocation.",
                "If 'Metaspace', audit recent classloader churn (hot reload, scripting, JSP)."))),
        Map.entry("ConcurrentModificationException", new Rule(
            "UNSAFE_CONCURRENT_ACCESS",
            "Collection mutated while iterated.",
            List.of(
                "Check whether the offending collection is shared across threads without synchronisation.",
                "Consider switching to a copy-on-write collection or snapshotting before iteration."))),
        Map.entry("SQLException", new Rule(
            "DATABASE_FAILURE",
            "JDBC operation failed — schema, connectivity, or DDL drift likely.",
            List.of(
                "Inspect the exception message for SQLSTATE / vendor error code.",
                "Verify recent Liquibase / Flyway migrations match production schema.",
                "Check connection-pool exhaustion if the message mentions timeouts."))),
        Map.entry("IllegalArgumentException", new Rule(
            "INVALID_INPUT",
            "A precondition check rejected the input on this path.",
            List.of(
                "Trace the top frame back to the validating call — log the rejected value.",
                "Examine recent changes that may have tightened validation."))),
        Map.entry("IllegalStateException", new Rule(
            "INVALID_STATE",
            "Object was used outside its valid lifecycle.",
            List.of(
                "Identify the lifecycle invariant broken by the top frame.",
                "Inspect recent refactors of the offending class for changed state machines."))),
        Map.entry("AssertionError", new Rule(
            "ASSERTION_FAILED",
            "An assertion (likely from a test or Guava/Spring precondition) failed.",
            List.of(
                "Print the assertion message and inputs near the top frame.",
                "Check whether a recent commit changed the invariant being asserted."))),
        Map.entry("TimeoutException", new Rule(
            "TIMEOUT",
            "An operation exceeded its configured deadline.",
            List.of(
                "Compare current timeouts against any recent config commits.",
                "Inspect downstream services involved in the failing flow."))),
        Map.entry("AccessDeniedException", new Rule(
            "PERMISSION_DENIED",
            "OS/Filesystem permission failure.",
            List.of(
                "Confirm the runtime user owns the path with adequate mode bits.",
                "Audit recent deployment scripts for permission changes.")))
    );

    private static final Rule DEFAULT_RULE = new Rule(
            "DOMAIN_EXCEPTION",
            "Domain or framework exception thrown deliberately on this path.",
            List.of(
                "Locate the throw site for this exception type in the repository.",
                "Examine recent commits touching that file for behaviour changes.",
                "Verify the caller is reacting to the exception as designed."));

    public List<RootCause> classify(ParsedStackTrace stack) {
        List<RootCause> out = new ArrayList<>();
        if (stack == null) return out;

        ExceptionLink primary = stack.primary();
        ExceptionLink rootCause = stack.rootCause();

        if (rootCause != null && rootCause != primary) {
            out.add(toCause(rootCause, stack, 0.85, "root of Caused-by chain"));
        }
        if (primary != null) {
            double conf = (rootCause == primary) ? 0.80 : 0.55;
            out.add(toCause(primary, stack, conf, "primary thrown exception"));
        }
        // De-dup by category, keep highest confidence.
        return dedupKeepBest(out);
    }

    private RootCause toCause(ExceptionLink link, ParsedStackTrace stack,
                              double baseConfidence, String reason) {
        Rule rule = lookup(link.exceptionFqn);
        RootCause rc = new RootCause(rule.category, rule.summary, baseConfidence);
        rc.suggestedSteps.addAll(rule.steps);
        rc.evidence.add("Exception: " + link.exceptionFqn
                + (link.message != null ? " — " + truncate(link.message, 240) : ""));
        rc.evidence.add("Reason: " + reason);
        if (!stack.frames.isEmpty()) {
            StackFrame top = stack.frames.get(0);
            rc.evidence.add("Top frame: " + top.displaySignature()
                    + (top.inRepo ? " [repo]" : " [external]"));
        }
        return rc;
    }

    private static Rule lookup(String exceptionFqn) {
        if (exceptionFqn == null) return DEFAULT_RULE;
        String simple = exceptionFqn.substring(exceptionFqn.lastIndexOf('.') + 1);
        Rule r = RULES.get(simple);
        return r != null ? r : DEFAULT_RULE;
    }

    private static List<RootCause> dedupKeepBest(List<RootCause> in) {
        List<RootCause> out = new ArrayList<>();
        outer:
        for (RootCause cand : in) {
            for (int i = 0; i < out.size(); i++) {
                RootCause kept = out.get(i);
                if (kept.category.equals(cand.category)) {
                    if (cand.confidence > kept.confidence) out.set(i, cand);
                    continue outer;
                }
            }
            out.add(cand);
        }
        out.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private record Rule(String category, String summary, List<String> steps) {}
}
