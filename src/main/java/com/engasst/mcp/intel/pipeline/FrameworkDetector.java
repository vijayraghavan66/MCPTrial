package com.engasst.mcp.intel.pipeline;

import com.engasst.mcp.intel.model.FrameworkRole;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.Set;

/**
 * Maps annotations / supertypes to a {@link FrameworkRole}. Centralised so
 * Spring, Servlet, JPA and messaging conventions live in one place.
 */
public final class FrameworkDetector {

    private static final Set<String> SERVLET_BASES = Set.of(
            "HttpServlet", "GenericServlet",
            "javax.servlet.http.HttpServlet", "jakarta.servlet.http.HttpServlet",
            "javax.servlet.GenericServlet", "jakarta.servlet.GenericServlet");
    private static final Set<String> FILTER_BASES = Set.of(
            "Filter", "javax.servlet.Filter", "jakarta.servlet.Filter");
    private static final Set<String> LISTENER_BASES = Set.of(
            "ServletContextListener", "HttpSessionListener", "ServletRequestListener",
            "javax.servlet.ServletContextListener", "jakarta.servlet.ServletContextListener");
    private static final Set<String> JPA_REPO_BASES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository", "Repository",
            "ReactiveCrudRepository", "MongoRepository");

    private static final Set<String> HTTP_METHOD_ANNS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
            "PatchMapping", "RequestMapping", "HttpExchange",
            "GET", "POST", "PUT", "DELETE", "Path");

    private FrameworkDetector() {}

    public static FrameworkRole detectType(TypeDeclaration<?> td) {
        // 1) annotations
        for (AnnotationExpr a : td.getAnnotations()) {
            String n = a.getNameAsString();
            switch (n) {
                case "RestController"   -> { return FrameworkRole.REST_CONTROLLER; }
                case "Controller"       -> { return FrameworkRole.CONTROLLER; }
                case "Service"          -> { return FrameworkRole.SERVICE; }
                case "Repository"       -> { return FrameworkRole.REPOSITORY; }
                case "Configuration"    -> { return FrameworkRole.CONFIGURATION; }
                case "Component"        -> { return FrameworkRole.COMPONENT; }
                case "Entity"           -> { return FrameworkRole.ENTITY; }
                case "WebServlet"       -> { return FrameworkRole.SERVLET; }
                case "WebFilter"        -> { return FrameworkRole.FILTER; }
                case "WebListener"      -> { return FrameworkRole.LISTENER; }
                default -> {}
            }
        }
        // 2) supertypes (Servlet stack & Spring Data)
        if (td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration c) {
            for (var ext : c.getExtendedTypes()) {
                if (SERVLET_BASES.contains(ext.getNameAsString()))  return FrameworkRole.SERVLET;
            }
            for (var impl : c.getImplementedTypes()) {
                String n = impl.getNameAsString();
                if (FILTER_BASES.contains(n))   return FrameworkRole.FILTER;
                if (LISTENER_BASES.contains(n)) return FrameworkRole.LISTENER;
                if (JPA_REPO_BASES.contains(n)) return FrameworkRole.JPA_REPOSITORY;
            }
        }
        return null;
    }

    public static FrameworkRole detectMethod(MethodDeclaration md, FrameworkRole owner) {
        for (AnnotationExpr a : md.getAnnotations()) {
            String n = a.getNameAsString();
            if (n.equals("Scheduled"))     return FrameworkRole.SCHEDULED;
            if (n.equals("EventListener")) return FrameworkRole.EVENT_LISTENER;
            if (n.equals("JmsListener") || n.equals("KafkaListener") || n.equals("RabbitListener"))
                return FrameworkRole.MESSAGE_LISTENER;
            if (HTTP_METHOD_ANNS.contains(n)) return FrameworkRole.HTTP_ENDPOINT;
        }
        // Servlet doGet/doPost on a servlet class
        if (owner == FrameworkRole.SERVLET && isServletEntry(md.getNameAsString()))
            return FrameworkRole.HTTP_ENDPOINT;
        return null;
    }

    private static boolean isServletEntry(String name) {
        return name.equals("doGet") || name.equals("doPost") || name.equals("doPut")
                || name.equals("doDelete") || name.equals("service");
    }

    public static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        for (AnnotationExpr a : node.getAnnotations())
            if (a.getNameAsString().equals(simpleName)) return true;
        return false;
    }
}
