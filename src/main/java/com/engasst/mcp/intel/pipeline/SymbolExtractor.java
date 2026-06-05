package com.engasst.mcp.intel.pipeline;

import com.engasst.mcp.intel.model.EdgeKind;
import com.engasst.mcp.intel.model.EdgeRow;
import com.engasst.mcp.intel.model.FrameworkRole;
import com.engasst.mcp.intel.model.SymbolKind;
import com.engasst.mcp.intel.model.SymbolRow;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a single {@link CompilationUnit} and produces:
 * <ul>
 *   <li>{@link SymbolRow} entries for every declared type, method, ctor, field;</li>
 *   <li>{@link EdgeRow} entries for inheritance, implementation, annotation,
 *       calls (instance/static), field reads/writes, NEWs and throws clauses.</li>
 * </ul>
 *
 * Targets are resolved via JavaParser's symbol solver when possible; otherwise
 * the (dst_fqn, dst_member) pair is left for the {@link IndexingPipeline}'s
 * Linker pass to wire up later.
 */
public final class SymbolExtractor {

    public record Extracted(List<SymbolRow> symbols, List<EdgeRow> edges,
                            List<AnnotationRow> annotations,
                            List<ImportRow> imports) {}

    public record AnnotationRow(SymbolRow target, String name, String fqn, String argsJson) {}
    public record ImportRow(String fqn, boolean isStatic, boolean isWildcard) {}

    private SymbolExtractor() {}

    public static Extracted extract(CompilationUnit cu, long repoId, long fileId) {
        List<SymbolRow> symbols = new ArrayList<>();
        List<EdgeRow> edges = new ArrayList<>();
        List<AnnotationRow> annotations = new ArrayList<>();
        List<ImportRow> imports = new ArrayList<>();

        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        for (ImportDeclaration imp : cu.getImports()) {
            imports.add(new ImportRow(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk()));
        }

        // local map of imported simple names → FQN, for heuristic resolution
        Map<String, String> importMap = new HashMap<>();
        for (ImportRow ir : imports) {
            if (ir.isWildcard) continue;
            int dot = ir.fqn.lastIndexOf('.');
            if (dot > 0) importMap.put(ir.fqn.substring(dot + 1), ir.fqn);
        }

        Deque<SymbolRow> typeStack = new ArrayDeque<>();
        for (TypeDeclaration<?> td : cu.getTypes()) {
            handleType(td, pkg, repoId, fileId, null, symbols, edges, annotations, importMap, typeStack);
        }

        return new Extracted(symbols, edges, annotations, imports);
    }

    // ---------------------------------------------------------------- types

    private static void handleType(TypeDeclaration<?> td, String pkg, long repoId, long fileId,
                                   SymbolRow parent,
                                   List<SymbolRow> symbols, List<EdgeRow> edges,
                                   List<AnnotationRow> annotations,
                                   Map<String, String> importMap,
                                   Deque<SymbolRow> typeStack) {
        SymbolRow s = new SymbolRow();
        s.repoId = repoId;
        s.fileId = fileId;
        s.parentRef = parent; // pipeline resolves to parentId after insert
        s.pkg = pkg;
        s.simpleName = td.getNameAsString();
        s.fqn = parent == null
                ? (pkg.isEmpty() ? s.simpleName : pkg + "." + s.simpleName)
                : parent.fqn + "." + s.simpleName;
        s.kind = kindOf(td);
        s.modifiers = modifiers(td);
        td.getBegin().ifPresent(p -> s.startLine = p.line);
        td.getEnd().ifPresent(p -> s.endLine = p.line);
        FrameworkRole role = FrameworkDetector.detectType(td);
        s.frameworkRole = role == null ? null : role.name();
        symbols.add(s);
        typeStack.push(s);

        // Annotations on the type → ANNOTATED_WITH edges + annotation rows
        for (AnnotationExpr a : td.getAnnotations()) {
            recordAnnotation(s, a, annotations, edges, repoId, fileId, importMap, pkg);
        }

        // Inheritance / implementation
        if (td instanceof ClassOrInterfaceDeclaration c) {
            for (ClassOrInterfaceType ext : c.getExtendedTypes())
                edges.add(typeEdge(repoId, fileId, s, ext, EdgeKind.EXTENDS, importMap, pkg));
            for (ClassOrInterfaceType impl : c.getImplementedTypes())
                edges.add(typeEdge(repoId, fileId, s, impl, EdgeKind.IMPLEMENTS, importMap, pkg));
        }

        // Fields
        for (FieldDeclaration f : td.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                SymbolRow fs = new SymbolRow();
                fs.repoId = repoId;
                fs.fileId = fileId;
                fs.parentRef = s;
                fs.kind = SymbolKind.FIELD;
                fs.pkg = pkg;
                fs.simpleName = v.getNameAsString();
                fs.fqn = s.fqn + "." + fs.simpleName;
                fs.signature = v.getType().asString() + " " + v.getNameAsString();
                fs.returnType = v.getType().asString();
                fs.modifiers = modifiers(f);
                f.getBegin().ifPresent(p -> fs.startLine = p.line);
                f.getEnd().ifPresent(p -> fs.endLine = p.line);
                symbols.add(fs);
                for (AnnotationExpr a : f.getAnnotations())
                    recordAnnotation(fs, a, annotations, edges, repoId, fileId, importMap, pkg);
            }
        }

        // Methods
        for (MethodDeclaration m : td.getMethods()) {
            SymbolRow ms = new SymbolRow();
            ms.repoId = repoId;
            ms.fileId = fileId;
            ms.parentRef = s;
            ms.kind = SymbolKind.METHOD;
            ms.pkg = pkg;
            ms.simpleName = m.getNameAsString();
            ms.fqn = s.fqn + "#" + m.getNameAsString();
            ms.signature = m.getDeclarationAsString(true, true, true);
            ms.returnType = m.getType().asString();
            ms.modifiers = modifiers(m);
            m.getBegin().ifPresent(p -> ms.startLine = p.line);
            m.getEnd().ifPresent(p -> ms.endLine = p.line);
            FrameworkRole mRole = FrameworkDetector.detectMethod(m, role);
            ms.frameworkRole = mRole == null ? null : mRole.name();
            symbols.add(ms);

            for (AnnotationExpr a : m.getAnnotations())
                recordAnnotation(ms, a, annotations, edges, repoId, fileId, importMap, pkg);
            for (var thr : m.getThrownExceptions()) {
                String typeName = thr.asString();
                EdgeRow er = newEdge(repoId, fileId, ms, EdgeKind.THROWS,
                        resolveTypeFqn(typeName, importMap, pkg), null,
                        m.getBegin().map(p -> p.line).orElse(null));
                edges.add(er);
            }
            collectBodyEdges(m, ms, repoId, fileId, edges, importMap, pkg, typeStack);
        }

        // Constructors
        boolean hasExplicitCtor = false;
        for (ConstructorDeclaration ctor : td.findAll(ConstructorDeclaration.class)) {
            if (ctor.findAncestor(TypeDeclaration.class).orElse(null) != td) continue;
            hasExplicitCtor = true;
            SymbolRow cs = new SymbolRow();
            cs.repoId = repoId;
            cs.fileId = fileId;
            cs.parentRef = s;
            cs.kind = SymbolKind.CONSTRUCTOR;
            cs.pkg = pkg;
            cs.simpleName = "<init>";
            cs.fqn = s.fqn + "#<init>";
            cs.signature = ctor.getDeclarationAsString(true, true, true);
            cs.paramTypes = paramTypesOfDecl(ctor.getParameters());
            cs.modifiers = modifiers(ctor);
            ctor.getBegin().ifPresent(p -> cs.startLine = p.line);
            ctor.getEnd().ifPresent(p -> cs.endLine = p.line);
            symbols.add(cs);
            for (AnnotationExpr a : ctor.getAnnotations())
                recordAnnotation(cs, a, annotations, edges, repoId, fileId, importMap, pkg);
            collectBodyEdges(ctor, cs, repoId, fileId, edges, importMap, pkg, typeStack);
            collectExplicitCtorInvocations(ctor, cs, repoId, fileId, edges, importMap, pkg, s);
        }
        // Synthesise the implicit default constructor for classes / enums / records
        // that declare none. Without this, every `new Foo()` against such a type
        // would dangle forever in the index.
        if (!hasExplicitCtor && shouldSynthDefaultCtor(s.kind)) {
            SymbolRow cs = new SymbolRow();
            cs.repoId = repoId;
            cs.fileId = fileId;
            cs.parentRef = s;
            cs.kind = SymbolKind.CONSTRUCTOR;
            cs.pkg = pkg;
            cs.simpleName = "<init>";
            cs.fqn = s.fqn + "#<init>";
            cs.signature = s.simpleName + "()";
            cs.paramTypes = "";
            cs.modifiers = "synthetic";
            cs.startLine = s.startLine;
            cs.endLine = s.startLine;
            symbols.add(cs);
        }

        // Nested types
        for (var member : td.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                handleType(nested, pkg, repoId, fileId, s, symbols, edges, annotations,
                        importMap, typeStack);
            }
        }

        typeStack.pop();
    }

    private static SymbolKind kindOf(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration c) return c.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
        if (td instanceof EnumDeclaration)               return SymbolKind.ENUM;
        if (td instanceof RecordDeclaration)             return SymbolKind.RECORD;
        if (td instanceof AnnotationDeclaration)         return SymbolKind.ANNOTATION_TYPE;
        return SymbolKind.CLASS;
    }

    private static String modifiers(com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> n) {
        StringBuilder sb = new StringBuilder();
        for (Modifier m : n.getModifiers()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(m.getKeyword().asString());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // -------------------------------------------------------- body traversal

    private static void collectBodyEdges(com.github.javaparser.ast.Node body, SymbolRow src,
                                         long repoId, long fileId, List<EdgeRow> edges,
                                         Map<String, String> importMap, String pkg,
                                         Deque<SymbolRow> typeStack) {
        // Method calls
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String dstFqn = null;
            String dstMember = call.getNameAsString();
            EdgeKind kind = EdgeKind.CALLS;
            String resolution = "unresolved";
            try {
                var rm = call.resolve();
                dstFqn = rm.declaringType().getQualifiedName();
                if (rm.isStatic()) kind = EdgeKind.STATIC_CALL;
                resolution = "ast";
            } catch (RuntimeException ignored) {
                dstFqn = heuristicTargetType(call, importMap, pkg, typeStack);
                if (dstFqn != null) resolution = "heuristic";
            }
            EdgeRow er = newEdge(repoId, fileId, src, kind, dstFqn, dstMember,
                    call.getBegin().map(p -> p.line).orElse(null));
            er.resolution = resolution;
            edges.add(er);
        }
        // Object creation
        for (ObjectCreationExpr nu : body.findAll(ObjectCreationExpr.class)) {
            String dstFqn = null;
            String paramTypes = null;
            String resolution = "unresolved";
            try {
                ResolvedConstructorDeclaration rc = nu.resolve();
                dstFqn = rc.declaringType().getQualifiedName();
                paramTypes = paramTypesOfResolved(rc);
                resolution = "ast";
            } catch (RuntimeException ignored) {
                dstFqn = resolveTypeFqn(nu.getType().getNameAsString(), importMap, pkg);
                if (dstFqn != null) resolution = "heuristic";
            }
            EdgeRow er = newEdge(repoId, fileId, src, EdgeKind.NEW, dstFqn, "<init>",
                    nu.getBegin().map(p -> p.line).orElse(null));
            er.paramTypes = paramTypes;
            er.resolution = resolution;
            edges.add(er);
        }
        // Field accesses
        for (FieldAccessExpr fa : body.findAll(FieldAccessExpr.class)) {
            String dstFqn = null, dstMember = fa.getNameAsString();
            try {
                var rv = fa.resolve();
                if (rv.isField()) dstFqn = rv.asField().declaringType().getQualifiedName();
            } catch (RuntimeException ignored) {
                dstFqn = heuristicTargetType(fa.getScope(), importMap, pkg, typeStack);
            }
            EdgeKind kind = isWriteContext(fa) ? EdgeKind.FIELD_WRITE : EdgeKind.FIELD_READ;
            edges.add(newEdge(repoId, fileId, src, kind, dstFqn, dstMember,
                    fa.getBegin().map(p -> p.line).orElse(null)));
        }
        // Assignments to plain names = potential field write on enclosing class
        for (AssignExpr ax : body.findAll(AssignExpr.class)) {
            if (ax.getTarget() instanceof NameExpr ne) {
                SymbolRow enclosingType = typeStack.peek();
                if (enclosingType == null) continue;
                edges.add(newEdge(repoId, fileId, src, EdgeKind.FIELD_WRITE,
                        enclosingType.fqn, ne.getNameAsString(),
                        ax.getBegin().map(p -> p.line).orElse(null)));
            }
        }
    }

    private static boolean isWriteContext(FieldAccessExpr fa) {
        return fa.getParentNode().map(p -> p instanceof AssignExpr ax && ax.getTarget() == fa).orElse(false);
    }

    private static String heuristicTargetType(MethodCallExpr call, Map<String, String> importMap,
                                              String pkg, Deque<SymbolRow> typeStack) {
        return call.getScope().map(scope -> heuristicTargetType(scope, importMap, pkg, typeStack))
                .orElseGet(() -> typeStack.peek() == null ? null : typeStack.peek().fqn);
    }

    private static String heuristicTargetType(com.github.javaparser.ast.Node node,
                                              Map<String, String> importMap, String pkg,
                                              Deque<SymbolRow> typeStack) {
        if (node instanceof NodeWithSimpleName<?> n) {
            String s = n.getNameAsString();
            String mapped = importMap.get(s);
            if (mapped != null) return mapped;
            // looks like a same-package type name?
            if (Character.isUpperCase(s.charAt(0))) {
                return pkg.isEmpty() ? s : pkg + "." + s;
            }
        }
        return null;
    }

    private static String resolveTypeFqn(String simple, Map<String, String> importMap, String pkg) {
        if (simple.contains(".")) return simple;
        String mapped = importMap.get(simple);
        if (mapped != null) return mapped;
        if (isJavaLang(simple)) return "java.lang." + simple;
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static boolean isJavaLang(String s) {
        return switch (s) {
            case "String", "Integer", "Long", "Boolean", "Double", "Float", "Object",
                 "Exception", "RuntimeException", "Throwable", "Thread", "Class",
                 "Number", "Byte", "Short", "Character" -> true;
            default -> false;
        };
    }

    /** True for kinds that get an implicit no-arg ctor when none is declared. */
    private static boolean shouldSynthDefaultCtor(SymbolKind k) {
        return k == SymbolKind.CLASS || k == SymbolKind.ENUM || k == SymbolKind.RECORD;
    }

    /**
     * Encode a declared parameter list as the Linker's overload key:
     * comma-joined erased simple type names, generics stripped, varargs marked
     * with a trailing {@code ...}. Empty string == no-arg.
     */
    private static String paramTypesOfDecl(List<Parameter> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Parameter p : params) {
            if (sb.length() > 0) sb.append(',');
            sb.append(normaliseParamType(p.getType().asString()));
            if (p.isVarArgs()) sb.append("...");
        }
        return sb.toString();
    }

    /** Same shape as {@link #paramTypesOfDecl} but driven by a resolved ctor. */
    private static String paramTypesOfResolved(ResolvedConstructorDeclaration rc) {
        int n = rc.getNumberOfParams();
        if (n == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (sb.length() > 0) sb.append(',');
            String t;
            try { t = rc.getParam(i).getType().describe(); }
            catch (RuntimeException ignored) { return null; }
            sb.append(normaliseParamType(t));
            try { if (rc.getParam(i).isVariadic()) sb.append("..."); }
            catch (RuntimeException ignored) {}
        }
        return sb.toString();
    }

    /** Strip generics ({@code List<String>} -> {@code List}) and any package prefix. */
    private static String normaliseParamType(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            int gt = t.lastIndexOf('>');
            t = gt > lt ? t.substring(0, lt) + t.substring(gt + 1) : t.substring(0, lt);
        }
        // preserve array brackets but strip package qualifier from the base
        int brk = t.indexOf('[');
        String base = brk >= 0 ? t.substring(0, brk) : t;
        String suffix = brk >= 0 ? t.substring(brk) : "";
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(dot + 1);
        return base + suffix;
    }

    /**
     * Record {@code super(...)} and {@code this(...)} invocations as NEW edges
     * from the enclosing constructor to the target type's constructor. Without
     * this, subclass -> superclass constructor links never exist in the graph.
     */
    private static void collectExplicitCtorInvocations(ConstructorDeclaration ctor, SymbolRow src,
                                                       long repoId, long fileId,
                                                       List<EdgeRow> edges,
                                                       Map<String, String> importMap,
                                                       String pkg, SymbolRow enclosingType) {
        for (ExplicitConstructorInvocationStmt eci : ctor.findAll(ExplicitConstructorInvocationStmt.class)) {
            String dstFqn = null;
            String paramTypes = null;
            String resolution = "unresolved";
            try {
                ResolvedConstructorDeclaration rc = eci.resolve();
                dstFqn = rc.declaringType().getQualifiedName();
                paramTypes = paramTypesOfResolved(rc);
                resolution = "ast";
            } catch (RuntimeException ignored) {
                // this(...) -> same class; super(...) -> we cannot recover the type
                // without resolution, so leave it null and let the Linker skip it.
                if (eci.isThis() && enclosingType != null) {
                    dstFqn = enclosingType.fqn;
                    resolution = "heuristic";
                }
            }
            EdgeRow er = newEdge(repoId, fileId, src, EdgeKind.NEW, dstFqn, "<init>",
                    eci.getBegin().map(p -> p.line).orElse(null));
            er.paramTypes = paramTypes;
            er.resolution = resolution;
            edges.add(er);
        }
    }

    // ----------------------------------------------------------------- util

    private static EdgeRow typeEdge(long repoId, long fileId, SymbolRow src, ClassOrInterfaceType t,
                                    EdgeKind kind, Map<String, String> importMap, String pkg) {
        String fqn;
        try {
            fqn = t.resolve().asReferenceType().getQualifiedName();
        } catch (RuntimeException ignored) {
            fqn = resolveTypeFqn(t.getNameAsString(), importMap, pkg);
        }
        return newEdge(repoId, fileId, src, kind, fqn, null,
                t.getBegin().map(p -> p.line).orElse(null));
    }

    private static EdgeRow newEdge(long repoId, long fileId, SymbolRow src, EdgeKind kind,
                                   String dstFqn, String dstMember, Integer line) {
        EdgeRow e = new EdgeRow();
        e.repoId = repoId;
        e.srcRef = src; // pipeline back-fills srcSymbolId after insert
        e.kind = kind;
        e.dstFqn = dstFqn;
        e.dstMember = dstMember;
        e.fileId = fileId;
        e.line = line;
        e.resolution = dstFqn == null ? "unresolved" : e.resolution;
        return e;
    }

    private static void recordAnnotation(SymbolRow target, AnnotationExpr a,
                                         List<AnnotationRow> annotations, List<EdgeRow> edges,
                                         long repoId, long fileId,
                                         Map<String, String> importMap, String pkg) {
        String simple = a.getNameAsString();
        String fqn = resolveTypeFqn(simple, importMap, pkg);
        annotations.add(new AnnotationRow(target, simple, fqn, null));
        edges.add(newEdge(repoId, fileId, target, EdgeKind.ANNOTATED_WITH, fqn, null,
                a.getBegin().map(p -> p.line).orElse(null)));
    }
}
