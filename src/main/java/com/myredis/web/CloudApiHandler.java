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
 * Serverless Cloud HTTP REST API for Redis (compatible with Upstash / Vercel KV style).
 * Allows edge, browser, and cloud serverless functions to interact with Redis over HTTP
 * with strict multi-tenant database virtualization, ACL, and RLS enforcement.
 */
public class CloudApiHandler implements HttpHandler {

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final AclEngine aclEngine;
    private final VirtualDatabaseManager virtualDbManager;
    private final int redisPort;
    private final int webPort;
    private final com.myredis.config.ServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public CloudApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                           VirtualDatabaseManager virtualDbManager, int redisPort, int webPort,
                           com.myredis.config.ServerConfig config) {
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.virtualDbManager = virtualDbManager;
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.webPort = webPort > 0 ? webPort : 8080;
        this.config = config;
    }

    public CloudApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                           VirtualDatabaseManager virtualDbManager, int redisPort, int webPort) {
        this(storage, registry, aclEngine, virtualDbManager, redisPort, webPort, null);
    }

    public CloudApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine) {
        this(storage, registry, aclEngine, null, 6379, 8080, null);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath(); // e.g. /v1/get/mykey or /v1/auth/signup

        // CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String cleanPath = path.toLowerCase();

        // 1. Public Auth Endpoints (No Bearer token required)
        if (cleanPath.equals("/v1/auth/signup") || cleanPath.equals("/v1/auth/register")) {
            if ("POST".equalsIgnoreCase(method)) {
                handleAuthSignup(exchange);
                return;
            }
        } else if (cleanPath.equals("/v1/auth/login")) {
            if ("POST".equalsIgnoreCase(method)) {
                handleAuthLogin(exchange);
                return;
            }
        }

        // 2. Authenticate via Bearer Token or Query Param
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        User user = aclEngine.authenticateToken(authHeader);

        // If no token in Authorization header, check query param ?token=...
        if (user == null) {
            Map<String, String> qp = parseQueryParams(uri.getQuery());
            if (qp.containsKey("token")) {
                user = aclEngine.authenticateToken(qp.get("token"));
            }
        }

        if (user == null) {
            sendJson(exchange, 401, Map.of(
                    "error", "Unauthorized: Valid Bearer token required in Authorization header (e.g. 'Bearer red_api_...')",
                    "hint", "Create an account via POST /v1/auth/signup or use 'tok_admin_live_secret'"
            ));
            return;
        }

        try {
            // Profile & Connection info
            if (cleanPath.equals("/v1/auth/me")) {
                String host = resolveHost(exchange);
                sendJson(exchange, 200, user.toMap(host, redisPort, webPort));
                return;
            }

            // Route /v1/...
            String subPath = path.startsWith("/v1/") ? path.substring(4) : path;
            String[] segments = Arrays.stream(subPath.split("/"))
                    .filter(s -> !s.isBlank())
                    .map(s -> URLDecoder.decode(s, StandardCharsets.UTF_8))
                    .toArray(String[]::new);

            if (segments.length == 0) {
                String host = resolveHost(exchange);
                sendJson(exchange, 200, Map.of(
                        "service", "Redis Cloud HTTP Command API (Java 25)",
                        "authenticated_as", user.getUsername(),
                        "role", user.getRole().name(),
                        "virtual_db", user.getVirtualDbName(),
                        "rls_patterns", user.getKeyPatterns(),
                        "account", user.toMap(host, redisPort, webPort)
                ));
                return;
            }

            String action = segments[0].toLowerCase();

            switch (action) {
                case "get" -> handleGet(exchange, segments, user);
                case "set" -> handleSet(exchange, user);
                case "del" -> handleDel(exchange, segments, user);
                case "lpush" -> handleLpush(exchange, segments, user);
                case "lrange" -> handleLrange(exchange, segments, user);
                case "hset" -> handleHset(exchange, segments, user);
                case "hgetall" -> handleHgetall(exchange, segments, user);
                case "pipeline" -> handlePipeline(exchange, user);
                case "command" -> handleGenericCommand(exchange, user);
                default -> {
                    // Fallback: /v1/:cmd/:key/:arg...
                    List<String> cmdArgs = new ArrayList<>();
                    cmdArgs.add(action.toUpperCase());
                    for (int i = 1; i < segments.length; i++) {
                        cmdArgs.add(segments[i]);
                    }
                    executeWithSecurity(exchange, cmdArgs, user);
                }
            }

        } catch (SecurityException se) {
            sendJson(exchange, 403, Map.of("error", se.getMessage(), "authenticated_user", user.getUsername()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error"));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleAuthSignup(HttpExchange exchange) throws IOException {
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
                    "message", "Tenant account provisioned with isolated database " + u.getVirtualDbName(),
                    "account", u.toMap(host, redisPort, webPort)
            ));
        } catch (IllegalArgumentException iae) {
            sendJson(exchange, 400, Map.of("error", iae.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleAuthLogin(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = body.isBlank() ? Collections.emptyMap() : mapper.readValue(body, Map.class);
        String username = (String) req.get("username");
        String password = (String) req.get("password");
        String token = (String) req.get("token");

        User u = null;
        if (token != null && !token.isBlank()) {
            u = aclEngine.authenticateSingleTokenOrPass(token);
        } else if (username != null && password != null) {
            u = aclEngine.authenticate(username, password);
        } else if (username != null) {
            u = aclEngine.authenticateSingleTokenOrPass(username);
        }

        if (u == null) {
            sendJson(exchange, 401, Map.of("error", "Invalid credentials"));
            return;
        }

        String host = resolveHost(exchange);
        sendJson(exchange, 200, Map.of(
                "success", true,
                "authenticated_as", u.getUsername(),
                "account", u.toMap(host, redisPort, webPort)
        ));
    }

    private StorageEngine resolveStorage(User user) {
        if (virtualDbManager != null && user != null) {
            return virtualDbManager.getStorageForUser(user.getUsername());
        }
        return storage;
    }

    private void handleGet(HttpExchange exchange, String[] segments, User user) throws IOException {
        if (segments.length < 2) {
            sendJson(exchange, 400, Map.of("error", "Key name missing in path (e.g. /v1/get/:key)"));
            return;
        }
        String key = segments[1];
        List<String> cmd = List.of("GET", key);
        executeWithSecurity(exchange, cmd, user);
    }

    @SuppressWarnings("unchecked")
    private void handleSet(HttpExchange exchange, User user) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String key = (String) req.get("key");
        Object val = req.get("value");
        Number ex = req.containsKey("ex") ? (Number) req.get("ex") : (Number) req.get("ttl");

        if (key == null || val == null) {
            sendJson(exchange, 400, Map.of("error", "Both 'key' and 'value' fields are required in JSON body"));
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("SET");
        cmd.add(key);
        cmd.add(String.valueOf(val));
        if (ex != null && ex.longValue() > 0) {
            cmd.add("EX");
            cmd.add(String.valueOf(ex.longValue()));
        }

        executeWithSecurity(exchange, cmd, user);
    }

    @SuppressWarnings("unchecked")
    private void handleDel(HttpExchange exchange, String[] segments, User user) throws IOException {
        List<String> keys = new ArrayList<>();
        if (segments.length >= 2) {
            keys.add(segments[1]);
        } else {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                Map<String, Object> req = mapper.readValue(body, Map.class);
                Object kObj = req.get("keys");
                if (kObj instanceof List<?> list) {
                    for (Object k : list) keys.add(String.valueOf(k));
                }
            }
        }

        if (keys.isEmpty()) {
            sendJson(exchange, 400, Map.of("error", "Missing keys to delete"));
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("DEL");
        cmd.addAll(keys);
        executeWithSecurity(exchange, cmd, user);
    }

    @SuppressWarnings("unchecked")
    private void handleLpush(HttpExchange exchange, String[] segments, User user) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String key = segments.length >= 2 ? segments[1] : (String) req.get("key");

        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Key name required in path or 'key' JSON property"));
            return;
        }

        Object valuesObj = req.containsKey("values") ? req.get("values") : req.get("value");

        List<String> cmd = new ArrayList<>();
        cmd.add("LPUSH");
        cmd.add(key);

        if (valuesObj instanceof List<?> list) {
            for (Object v : list) cmd.add(String.valueOf(v));
        } else if (valuesObj != null) {
            cmd.add(String.valueOf(valuesObj));
        }

        executeWithSecurity(exchange, cmd, user);
    }

    private void handleLrange(HttpExchange exchange, String[] segments, User user) throws IOException {
        if (segments.length < 2) {
            sendJson(exchange, 400, Map.of("error", "Key name required in path (e.g. /v1/lrange/:key)"));
            return;
        }
        String key = segments[1];
        Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
        String start = qp.getOrDefault("start", "0");
        String stop = qp.getOrDefault("stop", "-1");

        List<String> cmd = List.of("LRANGE", key, start, stop);
        executeWithSecurity(exchange, cmd, user);
    }

    @SuppressWarnings("unchecked")
    private void handleHset(HttpExchange exchange, String[] segments, User user) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String key = segments.length >= 2 ? segments[1] : (String) req.get("key");

        if (key == null || key.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Key name required in path or 'key' JSON property"));
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("HSET");
        cmd.add(key);

        if (req.containsKey("field") && req.containsKey("value")) {
            cmd.add(String.valueOf(req.get("field")));
            cmd.add(String.valueOf(req.get("value")));
        } else if (req.containsKey("fields") && req.get("fields") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                cmd.add(String.valueOf(e.getKey()));
                cmd.add(String.valueOf(e.getValue()));
            }
        } else if (req.containsKey("data") && req.get("data") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                cmd.add(String.valueOf(e.getKey()));
                cmd.add(String.valueOf(e.getValue()));
            }
        }

        executeWithSecurity(exchange, cmd, user);
    }

    private void handleHgetall(HttpExchange exchange, String[] segments, User user) throws IOException {
        if (segments.length < 2) {
            sendJson(exchange, 400, Map.of("error", "Key name required in path (e.g. /v1/hgetall/:key)"));
            return;
        }
        String key = segments[1];
        List<String> cmd = List.of("HGETALL", key);
        executeWithSecurity(exchange, cmd, user);
    }

    @SuppressWarnings("unchecked")
    private void handlePipeline(HttpExchange exchange, User user) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<?> commands;
        if (body.trim().startsWith("[")) {
            commands = mapper.readValue(body, List.class);
        } else {
            Map<?, ?> map = mapper.readValue(body, Map.class);
            Object cmds = map.get("commands");
            if (cmds instanceof List<?> l) {
                commands = l;
            } else {
                sendJson(exchange, 400, Map.of("error", "Expected JSON array of commands or object with 'commands' array"));
                return;
            }
        }

        StorageEngine target = resolveStorage(user);
        List<Object> results = new ArrayList<>();
        for (Object item : commands) {
            if (item instanceof List<?> cmdList) {
                List<String> strArgs = cmdList.stream().map(String::valueOf).toList();
                try {
                    aclEngine.verifyPermission(user, strArgs);
                    RespMessage resp = registry.execute(strArgs, target);
                    results.add(respToNative(resp));
                } catch (SecurityException se) {
                    results.add(Map.of("error", se.getMessage()));
                }
            }
        }

        sendJson(exchange, 200, Map.of("results", results));
    }

    @SuppressWarnings("unchecked")
    private void handleGenericCommand(HttpExchange exchange, User user) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        Object cmdObj = req.get("command");
        Object argsObj = req.get("args");

        List<String> tokens = new ArrayList<>();
        if (cmdObj instanceof List<?> l) {
            tokens.addAll(l.stream().map(String::valueOf).toList());
        } else if (cmdObj instanceof String s) {
            tokens.addAll(Arrays.stream(s.trim().split("\\s+")).toList());
            if (argsObj instanceof List<?> l) {
                for (Object a : l) tokens.add(String.valueOf(a));
            }
        } else {
            sendJson(exchange, 400, Map.of("error", "Missing 'command' in JSON request"));
            return;
        }

        executeWithSecurity(exchange, tokens, user);
    }

    private void executeWithSecurity(HttpExchange exchange, List<String> cmdArgs, User user) throws IOException {
        // Enforce ACL + RLS
        aclEngine.verifyPermission(user, cmdArgs);

        StorageEngine target = resolveStorage(user);
        RespMessage resp = registry.execute(cmdArgs, target);
        Object nativeVal = respToNative(resp);

        if (resp.getType() == com.myredis.protocol.RespType.ERROR) {
            sendJson(exchange, 400, Map.of("error", resp.getStringValue()));
        } else {
            sendJson(exchange, 200, Map.of("result", nativeVal != null ? nativeVal : "null"));
        }
    }

    private Object respToNative(RespMessage msg) {
        if (msg == null || msg.isNull()) {
            return null;
        }
        return switch (msg.getType()) {
            case SIMPLE_STRING -> msg.getStringValue();
            case INTEGER -> msg.getIntegerValue();
            case BULK_STRING -> msg.getStringValue();
            case ERROR -> "(error) " + msg.getStringValue();
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (RespMessage m : msg.getArrayValue()) {
                    list.add(respToNative(m));
                }
                yield list;
            }
        };
    }

    private String resolveHost(HttpExchange exchange) {
        if (config != null && config.getPublicHost() != null && !config.getPublicHost().isBlank()) {
            return config.getPublicHost();
        }
        String fwd = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (fwd != null && !fwd.isBlank()) {
            int colon = fwd.indexOf(':');
            return colon > 0 ? fwd.substring(0, colon) : fwd;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isBlank()) {
            int colon = host.indexOf(':');
            return colon > 0 ? host.substring(0, colon) : host;
        }
        if (config != null && config.getVpcPrivateIp() != null) {
            return config.getVpcPrivateIp();
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
            }
        }
        return params;
    }
}
