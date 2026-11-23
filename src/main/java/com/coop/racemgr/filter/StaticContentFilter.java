package com.coop.racemgr.filter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

// Custom static asset handler to serve the React frontend as part of the JAR
// Refs:
// 1) https://stackoverflow.com/a/68193822
// 2) https://www.baeldung.com/spring-mvc-static-resources
@Component
public class StaticContentFilter implements Filter {
    // Extensions treated as static assets, with the content type to send for each
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("css", "text/css"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("csv", "text/csv"),
            Map.entry("txt", "text/plain"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("appcache", "text/cache-manifest")
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        doFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
    }

    private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        // servletPath is the full path with the default "/" servlet mapping; add pathInfo so this
        // also works when the dispatcher servlet has a prefix mapping.
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());

        // Never let a request escape the static folder
        if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
            response.sendError(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase());
            return;
        }

        boolean isApi = path.equals("/api") || path.startsWith("/api/");
        if (isApi) {
            chain.doFilter(request, response);
            return;
        }

        String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1);

        if (CONTENT_TYPES.containsKey(extension)) {
            resourceToResponse("static" + path, CONTENT_TYPES.get(extension), response);
        } else {
            // Single page app: any other path is a client-side route
            resourceToResponse("static/index.html", CONTENT_TYPES.get("html"), response);
        }
    }

    private void resourceToResponse(String resourcePath, String contentType, HttpServletResponse response) throws IOException {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                response.sendError(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.getReasonPhrase());
                return;
            }

            response.setContentType(contentType);
            // Vite names bundles /assets/<name>-<content hash>.<ext>, so they can be cached forever
            if (resourcePath.startsWith("static/assets/")) {
                response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            }
            inputStream.transferTo(response.getOutputStream());
        }
    }
}
