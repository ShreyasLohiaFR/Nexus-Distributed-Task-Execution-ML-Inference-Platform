package com.nexus;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class WorkspaceAuth extends OncePerRequestFilter {
    private final byte[] key;

    public WorkspaceAuth(@Value("${nexus.api-key}") String apiKey) {
        if (apiKey.length() < 24) throw new IllegalStateException("NEXUS_API_KEY must contain at least 24 characters");
        this.key = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    private boolean requiresKey(String path) {
        // Deny by default: only these exact read-only assets and health are public.
        return !java.util.Set.of("/", "/index.html", "/style.css", "/app.js", "/actuator/health", "/favicon.ico").contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        if (requiresKey(path) || !java.util.Set.of("GET", "HEAD").contains(request.getMethod())) {
            response.setHeader("Cache-Control", "no-store");
            String supplied = request.getHeader("X-API-Key");
            if (supplied == null || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(401);
                return;
            }
            long length = request.getContentLengthLong();
            if (length > 131072) {
                response.sendError(413);
                return;
            }
            // The JSON submission API requires a known length. Other endpoints have no body.
            if ("POST".equals(request.getMethod()) && length < 0) {
                response.sendError(411);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
