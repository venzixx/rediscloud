package com.myredis.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myredis.commands.CommandRegistry;
import com.myredis.protocol.RespMessage;
import com.myredis.storage.DataType;
import com.myredis.storage.StorageEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST API handler for the Redis Web Dashboard and management console.
 */
public class ApiHandler implements HttpHandler {

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiHandler(StorageEngine storage, CommandRegistry registry) {
        this.storage = storage;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        // Enable CORS for frontend development
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            if (path.equals("/api/stats") && "GET".equals(method)) {
                handleStats(exchange);
            } else if (path.equals("/api/keys") && "GET".equals(method)) {
                handleListKeys(exchange);
            } else if (path.equals("/api/key") && "GET".equals(method)) {
                handleGetKey(exchange);
            } else if (path.equals("/api/key") && "POST".equals(method)) {
                handleSaveKey(exchange);
            } else if (path.equals("/api/key") && "DELETE".equals(method)) {
                handleDeleteKey(exchange);
            } else if (path.equals("/api/exec") && "POST".equals(method)) {
                handleExecCommand(exchange);
            } else if (path.equals("/api/flush") && "POST".equals(method)) {
                handleFlush(exchange);
            } else {
                sendJson(exchange, 404, Map.of("error", "Endpoint not found: " + path));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error"));
        }
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = storage.getMetrics().toMap(storage.dbSize());
        sendJson(exchange, 200, stats);
    }

    private void handleListKeys(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        String pattern = queryParams.getOrDefault("pattern", "*");
        String typeFilter = queryParams.get("type");

        List<Map<String, Object>> allKeys = storage.getAllKeysMetadata();
        List<Map<String, Object>> filtered = new ArrayList<>();

        for (Map<String, Object> meta : allKeys) {
            String key = (String) meta.get("key");
            String type = (String) meta.get("type");

            if (typeFilter != null && !typeFilter.equalsIgnoreCase("ALL") && !typeFilter.equalsIgnoreCase(type)) {
                continue;
            }

            if (!pattern.equals("*") && !key.toLowerCase().contains(pattern.toLowerCase().replace("*", ""))) {
                continue;
            }

            filtered.add(meta);
        }

        // Sort by key name
        filtered.sort(Comparator.comparing(m -> (String) m.get("key")));

        sendJson(exchange, 200, Map.of(
                "total", filtered.size(),
                "keys", filtered
        ));
    }

    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        String key = queryParams.get("key");
        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing 'key' query parameter"));
            return;
        }

        Map<String, Object> details = storage.getKeyDetails(key);
        if (details == null) {
            sendJson(exchange, 404, Map.of("error", "Key not found or expired"));
            return;
        }

        sendJson(exchange, 200, details);
    }

    @SuppressWarnings("unchecked")
    private void handleSaveKey(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = mapper.readValue(body, Map.class);

        String key = (String) request.get("key");
        String type = (String) request.getOrDefault("type", "string");
        Object valObj = request.get("value");
        Number ttlNum = (Number) request.get("ttl");

        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Key name is required"));
            return;
        }

        Long expireAtMillis = null;
        if (ttlNum != null && ttlNum.longValue() > 0) {
            expireAtMillis = System.currentTimeMillis() + (ttlNum.longValue() * 1000L);
        }

        switch (type.toLowerCase()) {
            case "string" -> {
                String strVal = valObj != null ? valObj.toString() : "";
                storage.set(key, strVal, expireAtMillis, false, false);
            }
            case "hash" -> {
                storage.del(List.of(key));
                Map<String, String> hash = new HashMap<>();
                if (valObj instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        hash.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                storage.hset(key, hash);
                if (expireAtMillis != null) storage.pexpire(key, expireAtMillis - System.currentTimeMillis());
            }
            case "list" -> {
                storage.del(List.of(key));
                List<String> list = new ArrayList<>();
                if (valObj instanceof List<?> l) {
                    for (Object item : l) list.add(String.valueOf(item));
                } else if (valObj instanceof String s) {
                    for (String item : s.split(",")) list.add(item.trim());
                }
                if (!list.isEmpty()) storage.rpush(key, list);
                if (expireAtMillis != null) storage.pexpire(key, expireAtMillis - System.currentTimeMillis());
            }
            case "set" -> {
                storage.del(List.of(key));
                List<String> set = new ArrayList<>();
                if (valObj instanceof List<?> l) {
                    for (Object item : l) set.add(String.valueOf(item));
                } else if (valObj instanceof String s) {
                    for (String item : s.split(",")) set.add(item.trim());
                }
                if (!set.isEmpty()) storage.sadd(key, set);
                if (expireAtMillis != null) storage.pexpire(key, expireAtMillis - System.currentTimeMillis());
            }
            default -> {
                sendJson(exchange, 400, Map.of("error", "Unsupported type: " + type));
                return;
            }
        }

        sendJson(exchange, 200, Map.of("success", true, "message", "Key saved successfully"));
    }

    private void handleDeleteKey(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        String key = queryParams.get("key");
        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing 'key' query parameter"));
            return;
        }

        int count = storage.del(List.of(key));
        sendJson(exchange, 200, Map.of("success", true, "deletedCount", count));
    }

    @SuppressWarnings("unchecked")
    private void handleExecCommand(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String rawCommand = (String) req.get("command");

        if (rawCommand == null || rawCommand.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Command string cannot be empty"));
            return;
        }

        List<String> tokens = tokenize(rawCommand.trim());
        if (tokens.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "No command specified"));
            return;
        }

        RespMessage resp = registry.execute(tokens, storage);
        String formattedOutput = formatRespHuman(resp);

        sendJson(exchange, 200, Map.of(
                "success", resp.getType() != com.myredis.protocol.RespType.ERROR,
                "type", resp.getType().name(),
                "output", formattedOutput
        ));
    }

    private void handleFlush(HttpExchange exchange) throws IOException {
        storage.flushDb();
        sendJson(exchange, 200, Map.of("success", true, "message", "Database flushed"));
    }

    private String formatRespHuman(RespMessage msg) {
        if (msg == null || msg.isNull()) {
            return "(nil)";
        }
        return switch (msg.getType()) {
            case SIMPLE_STRING -> msg.getStringValue();
            case ERROR -> "(error) " + msg.getStringValue();
            case INTEGER -> "(integer) " + msg.getIntegerValue();
            case BULK_STRING -> "\"" + msg.getStringValue() + "\"";
            case ARRAY -> {
                StringBuilder sb = new StringBuilder();
                List<RespMessage> items = msg.getArrayValue();
                for (int i = 0; i < items.size(); i++) {
                    sb.append(i + 1).append(") ").append(formatRespHuman(items.get(i)));
                    if (i < items.size() - 1) sb.append("\n");
                }
                yield sb.toString();
            }
        };
    }

    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isBlank()) return Collections.emptyMap();
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            } else if (!pair.isBlank()) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return params;
    }

    private static List<String> tokenize(String input) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
                quoteChar = 0;
            } else if (!inQuote && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    list.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            list.add(cur.toString());
        }
        return list;
    }
}
