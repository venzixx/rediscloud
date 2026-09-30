package com.myredis.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves embedded static assets (HTML, CSS, JS) for the Web Dashboard.
 */
public class StaticResourceHandler implements HttpHandler {

    private static final String RESOURCE_BASE = "/web";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        // Prevent path traversal
        if (path.contains("..")) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        String resourcePath = RESOURCE_BASE + path;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // If not found and not an API call, fallback to index.html for SPA routing
                try (InputStream fallback = getClass().getResourceAsStream(RESOURCE_BASE + "/index.html")) {
                    if (fallback != null) {
                        byte[] bytes = fallback.readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(bytes);
                        }
                        return;
                    }
                }
                sendError(exchange, 404, "File not found: " + path);
                return;
            }

            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", getMimeType(path));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
