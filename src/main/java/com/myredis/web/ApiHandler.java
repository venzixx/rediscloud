package com.myredis.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myredis.commands.CommandRegistry;
import com.myredis.protocol.RespMessage;
import com.myredis.security.AclEngine;
import com.myredis.security.User;
import com.myredis.storage.StorageEngine;
import com.myredis.storage.VirtualDatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST API handler for the Redis Web Dashboard and management console.
 * Supports multi-tenant virtual database selection and tenant account provisioning.
 */
public class ApiHandler implements HttpHandler {

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final AclEngine aclEngine;
    private final VirtualDatabaseManager virtualDbManager;
    private final int redisPort;
    private final int webPort;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                      VirtualDatabaseManager virtualDbManager, int redisPort, int webPort) {
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.virtualDbManager = virtualDbManager;
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.webPort = webPort > 0 ? webPort : 8080;
    }

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine) {
        this(storage, registry, aclEngine, null, 6379, 8080);
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
            } else if (path.equals("/api/batch-delete") && "POST".equals(method)) {
                handleBatchDelete(exchange);
            } else if (path.equals("/api/export") && "GET".equals(method)) {
                handleExport(exchange);
            } else if (path.equals("/api/import") && "POST".equals(method)) {
                handleImport(exchange);
            } else if (path.equals("/api/acl") && "GET".equals(method)) {
                handleAclList(exchange);
            } else if (path.equals("/api/tenants") && "GET".equals(method)) {
                handleListTenants(exchange);
            } else if (path.equals("/api/tenants") && "POST".equals(method)) {
                handleCreateTenant(exchange);
            } else if (path.equals("/api/auth/register") && "POST".equals(method)) {
                handleCreateTenant(exchange);
            } else if (path.equals("/api/benchmark") && "POST".equals(method)) {
                handleBenchmark(exchange);
            } else if (path.equals("/api/flush") && "POST".equals(method)) {
                handleFlush(exchange);
            } else {
                sendJson(exchange, 404, Map.of("error", "Endpoint not found: " + path));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error"));
        }
    }

    private StorageEngine resolveStorage(Map<String, String> queryParams) {
        if (virtualDbManager == null) return storage;
        String tenant = queryParams.get("tenant");
        if (tenant != null && !tenant.isBlank()) {
            return virtualDbManager.getStorageForUser(tenant);
        }
        String db = queryParams.get("db");
        if (db != null && !db.isBlank()) {
            return virtualDbManager.getStorageByDbName(db);
        }
        return storage;
    }

    private StorageEngine resolveStorage(Map<String, Object> req, Map<String, String> queryParams) {
        if (virtualDbManager == null) return storage;
        if (req != null) {
            if (req.containsKey("tenant") && req.get("tenant") != null) {
                return virtualDbManager.getStorageForUser(String.valueOf(req.get("tenant")));
            }
            if (req.containsKey("db") && req.get("db") != null) {
                return virtualDbManager.getStorageByDbName(String.valueOf(req.get("db")));
            }
        }
        return resolveStorage(queryParams);
    }

    private String getActiveDbName(Map<String, String> qp) {
        if (qp.containsKey("tenant") && !qp.get("tenant").isBlank()) {
            return "vdb_" + qp.get("tenant").toLowerCase();
        }
        if (qp.containsKey("db") && !qp.get("db").isBlank()) {
            return qp.get("db");
        }
        return "db0";
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(qp);
        Map<String, Object> stats = target.getMetrics().toMap(target.dbSize());
        stats.put("activeDb", getActiveDbName(qp));
        stats.put("databases", virtualDbManager != null ? virtualDbManager.listDatabases() : List.of());
        sendJson(exchange, 200, stats);
    }

    private void handleListTenants(HttpExchange exchange) throws IOException {
        String host = resolveHost(exchange);
        List<Map<String, Object>> users = aclEngine != null ? aclEngine.listUsersWithUrls(host, redisPort, webPort) : Collections.emptyList();
        if (virtualDbManager != null) {
            for (Map<String, Object> uMap : users) {
                String username = (String) uMap.get("username");
                StorageEngine se = virtualDbManager.getStorageForUser(username);
                uMap.put("keyCount", se.dbSize());
                uMap.put("memoryBytes", se.getMetrics().getUsedMemoryBytes());
                uMap.put("memoryHuman", se.getMetrics().getUsedMemoryHuman());
            }
        }
        List<Map<String, Object>> dbs = virtualDbManager != null ? virtualDbManager.listDatabases() : List.of();
        sendJson(exchange, 200, Map.of(
                "tenants", users,
                "databases", dbs
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleCreateTenant(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = body.isBlank() ? Collections.emptyMap() : mapper.readValue(body, Map.class);
        String username = (String) req.get("username");
        String password = (String) req.get("password");
        String token = (String) req.get("token");

        if (username == null || username.trim().isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "Username is required"));
            return;
        }

        try {
            User u = aclEngine.createTenantAccount(username, password, token);
            if (virtualDbManager != null) {
                virtualDbManager.getStorageForUser(u.getUsername());
            }
            String host = resolveHost(exchange);
            sendJson(exchange, 201, Map.of(
                    "success", true,
                    "message", "Tenant account provisioned with virtual database " + u.getVirtualDbName(),
                    "user", u.toMap(host, redisPort, webPort)
            ));
        } catch (IllegalArgumentException iae) {
            sendJson(exchange, 400, Map.of("error", iae.getMessage()));
        }
    }

    private void handleListKeys(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(queryParams);
        String pattern = queryParams.getOrDefault("pattern", "*");
        String typeFilter = queryParams.get("type");
        String namespace = queryParams.get("namespace");
        String sort = queryParams.getOrDefault("sort", "key_asc");

        int page = 1;
        int limit = 50;
        try {
            if (queryParams.containsKey("page")) page = Math.max(1, Integer.parseInt(queryParams.get("page")));
            if (queryParams.containsKey("limit")) limit = Math.max(5, Math.min(500, Integer.parseInt(queryParams.get("limit"))));
        } catch (NumberFormatException ignored) {}

        StorageEngine.KeyPageResult result = target.queryKeys(pattern, typeFilter, namespace, sort, page, limit);
        sendJson(exchange, 200, result);
    }

    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(queryParams);
        String key = queryParams.get("key");
        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing 'key' query parameter"));
            return;
        }

        Map<String, Object> details = target.getKeyDetails(key);
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
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(request, queryParams);

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
                target.set(key, strVal, expireAtMillis, false, false);
            }
            case "hash" -> {
                target.del(List.of(key));
                Map<String, String> hash = new HashMap<>();
                if (valObj instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        hash.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                target.hset(key, hash);
                if (expireAtMillis != null) target.pexpire(key, expireAtMillis - System.currentTimeMillis());
            }
            case "list" -> {
                target.del(List.of(key));
                List<String> list = new ArrayList<>();
                if (valObj instanceof List<?> l) {
                    for (Object item : l) list.add(String.valueOf(item));
                } else if (valObj instanceof String s) {
                    for (String item : s.split(",")) list.add(item.trim());
                }
                if (!list.isEmpty()) target.rpush(key, list);
                if (expireAtMillis != null) target.pexpire(key, expireAtMillis - System.currentTimeMillis());
            }
            case "set" -> {
                target.del(List.of(key));
                List<String> set = new ArrayList<>();
                if (valObj instanceof List<?> l) {
                    for (Object item : l) set.add(String.valueOf(item));
                } else if (valObj instanceof String s) {
                    for (String item : s.split(",")) set.add(item.trim());
                }
                if (!set.isEmpty()) target.sadd(key, set);
                if (expireAtMillis != null) target.pexpire(key, expireAtMillis - System.currentTimeMillis());
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
        StorageEngine target = resolveStorage(queryParams);
        String key = queryParams.get("key");
        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Missing 'key' query parameter"));
            return;
        }

        int count = target.del(List.of(key));
        sendJson(exchange, 200, Map.of("success", true, "deletedCount", count));
    }

    @SuppressWarnings("unchecked")
    private void handleExecCommand(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(req, queryParams);

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

        RespMessage resp = registry.execute(tokens, target);
        String formattedOutput = formatRespHuman(resp);

        sendJson(exchange, 200, Map.of(
                "success", resp.getType() != com.myredis.protocol.RespType.ERROR,
                "type", resp.getType().name(),
                "output", formattedOutput
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleBatchDelete(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(req, queryParams);

        List<String> keys = (List<String>) req.get("keys");
        if (keys == null || keys.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "No keys provided for deletion"));
            return;
        }
        int deleted = target.del(keys);
        sendJson(exchange, 200, Map.of("success", true, "deletedCount", deleted));
    }

    private void handleExport(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(queryParams);
        List<Map<String, Object>> data = target.exportData();
        Map<String, Object> export = Map.of(
                "exportedAt", System.currentTimeMillis(),
                "totalKeys", data.size(),
                "keys", data
        );
        sendJson(exchange, 200, export);
    }

    @SuppressWarnings("unchecked")
    private void handleImport(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(req, queryParams);

        List<Map<String, Object>> keys = (List<Map<String, Object>>) req.get("keys");
        if (keys == null) {
            sendJson(exchange, 400, Map.of("error", "Invalid import format: missing 'keys' list"));
            return;
        }

        int imported = 0;
        for (Map<String, Object> item : keys) {
            String key = (String) item.get("key");
            String type = (String) item.getOrDefault("type", "string");
            Object val = item.get("value");
            Number ttl = (Number) item.get("ttl");
            if (key != null && val != null) {
                Long expireAt = (ttl != null && ttl.longValue() > 0) ? System.currentTimeMillis() + (ttl.longValue() * 1000L) : null;
                switch (type.toLowerCase()) {
                    case "string" -> target.set(key, val.toString(), expireAt, false, false);
                    case "hash" -> {
                        Map<String, String> h = new HashMap<>();
                        if (val instanceof Map<?, ?> m) {
                            for (Map.Entry<?, ?> e : m.entrySet()) h.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        }
                        target.hset(key, h);
                        if (expireAt != null) target.pexpire(key, expireAt - System.currentTimeMillis());
                    }
                    case "list" -> {
                        List<String> l = new ArrayList<>();
                        if (val instanceof List<?> list) {
                            for (Object o : list) l.add(String.valueOf(o));
                        }
                        if (!l.isEmpty()) target.rpush(key, l);
                        if (expireAt != null) target.pexpire(key, expireAt - System.currentTimeMillis());
                    }
                    case "set" -> {
                        List<String> s = new ArrayList<>();
                        if (val instanceof List<?> set) {
                            for (Object o : set) s.add(String.valueOf(o));
                        }
                        if (!s.isEmpty()) target.sadd(key, s);
                        if (expireAt != null) target.pexpire(key, expireAt - System.currentTimeMillis());
                    }
                }
                imported++;
            }
        }

        sendJson(exchange, 200, Map.of("success", true, "importedCount", imported));
    }

    private void handleAclList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> users = aclEngine != null ? aclEngine.listUsersSummary() : Collections.emptyList();
        sendJson(exchange, 200, Map.of("users", users));
    }

    @SuppressWarnings("unchecked")
    private void handleBenchmark(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(queryParams);

        int totalOps = 10000;
        int concurrency = 50;

        if (!body.isBlank()) {
            try {
                Map<String, Object> req = mapper.readValue(body, Map.class);
                if (req.containsKey("totalOperations")) totalOps = ((Number) req.get("totalOperations")).intValue();
                if (req.containsKey("concurrency")) concurrency = ((Number) req.get("concurrency")).intValue();
            } catch (Exception ignored) {}
        }

        try {
            com.myredis.benchmark.BenchmarkEngine.BenchmarkResult res =
                    com.myredis.benchmark.BenchmarkEngine.runBenchmark(target, registry, totalOps, concurrency);
            sendJson(exchange, 200, res);
        } catch (InterruptedException e) {
            sendJson(exchange, 500, Map.of("error", "Benchmark interrupted"));
        }
    }

    private void handleFlush(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        StorageEngine target = resolveStorage(queryParams);
        target.flushDb();
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

    private String resolveHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isBlank()) {
            int colon = host.indexOf(':');
            return colon > 0 ? host.substring(0, colon) : host;
        }
        return "localhost";
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
