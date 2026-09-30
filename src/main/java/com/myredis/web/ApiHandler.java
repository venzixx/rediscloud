package com.myredis.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myredis.commands.CommandRegistry;
import com.myredis.protocol.RespMessage;
import com.myredis.security.Account;
import com.myredis.security.AccountManager;
import com.myredis.security.AclEngine;
import com.myredis.security.Session;
import com.myredis.security.User;
import com.myredis.storage.DatabaseInstance;
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
 * REST API handler for the Redis Cloud Platform & Database Studio.
 * Supports Email/Google Sign-In, multi-database creation, sharing with collaborators,
 * and permission enforcement.
 */
public class ApiHandler implements HttpHandler {

    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final AclEngine aclEngine;
    private final VirtualDatabaseManager virtualDbManager;
    private final AccountManager accountManager;
    private final int redisPort;
    private final int webPort;
    private final com.myredis.config.ServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                      VirtualDatabaseManager virtualDbManager, AccountManager accountManager,
                      int redisPort, int webPort, com.myredis.config.ServerConfig config) {
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.virtualDbManager = virtualDbManager;
        this.accountManager = accountManager;
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.webPort = webPort > 0 ? webPort : 8080;
        this.config = config;
    }

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                      VirtualDatabaseManager virtualDbManager, AccountManager accountManager,
                      int redisPort, int webPort) {
        this(storage, registry, aclEngine, virtualDbManager, accountManager, redisPort, webPort, null);
    }

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine,
                      VirtualDatabaseManager virtualDbManager, int redisPort, int webPort) {
        this(storage, registry, aclEngine, virtualDbManager, null, redisPort, webPort, null);
    }

    public ApiHandler(StorageEngine storage, CommandRegistry registry, AclEngine aclEngine) {
        this(storage, registry, aclEngine, null, null, 6379, 8080, null);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        // Enable CORS for frontend development
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        try {
            // Authentication & User Identity Routes
            if (path.equals("/api/auth/register") && "POST".equals(method)) {
                handleAuthRegister(exchange);
            } else if (path.equals("/api/auth/login") && "POST".equals(method)) {
                handleAuthLogin(exchange);
            } else if (path.equals("/api/auth/google") && "POST".equals(method)) {
                handleAuthGoogle(exchange);
            } else if (path.equals("/api/auth/me") && "GET".equals(method)) {
                handleAuthMe(exchange);
            } else if (path.equals("/api/auth/logout") && "POST".equals(method)) {
                handleAuthLogout(exchange);
            } else if (path.equals("/api/auth/demo-users") && "GET".equals(method)) {
                handleAuthDemoUsers(exchange);

            // Database Management & Sharing Routes
            } else if (path.equals("/api/databases") && "GET".equals(method)) {
                handleListDatabases(exchange);
            } else if (path.equals("/api/databases") && "POST".equals(method)) {
                handleCreateDatabase(exchange);
            } else if (path.equals("/api/databases/share") && "POST".equals(method)) {
                handleShareDatabase(exchange);
            } else if (path.equals("/api/databases/unshare") && "POST".equals(method)) {
                handleUnshareDatabase(exchange);
            } else if (path.equals("/api/databases/share-link") && "POST".equals(method)) {
                handleShareLink(exchange);
            } else if (path.equals("/api/databases/join") && "POST".equals(method)) {
                handleJoinShareLink(exchange);
            } else if (path.equals("/api/databases/ip-routing") && "POST".equals(method)) {
                handleDatabaseIpRouting(exchange);
            } else if ((path.equals("/api/network/info") || path.equals("/api/network/routing")) && "GET".equals(method)) {
                handleNetworkInfo(exchange);
            } else if (path.startsWith("/api/databases/") && "DELETE".equals(method)) {
                handleDeleteDatabase(exchange, path.substring("/api/databases/".length()));
            } else if (path.equals("/api/database") && "DELETE".equals(method)) {
                Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
                handleDeleteDatabase(exchange, qp.get("id"));

            // Core Redis Data & Telemetry Operations
            } else if (path.equals("/api/stats") && "GET".equals(method)) {
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
                handleListDatabases(exchange); // Aliased for backward compatibility
            } else if (path.equals("/api/benchmark") && "POST".equals(method)) {
                handleBenchmark(exchange);
            } else if (path.equals("/api/flush") && "POST".equals(method)) {
                handleFlush(exchange);
            } else {
                sendJson(exchange, 404, Map.of("error", "Endpoint not found: " + path));
            }
        } catch (SecurityException se) {
            sendJson(exchange, 403, Map.of("error", se.getMessage()));
        } catch (IllegalArgumentException iae) {
            sendJson(exchange, 400, Map.of("error", iae.getMessage()));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error"));
        }
    }

    // ==========================================
    // User Identity & Authentication Handlers
    // ==========================================

    @SuppressWarnings("unchecked")
    private void handleAuthRegister(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String email = (String) req.get("email");
        String password = (String) req.get("password");
        String name = (String) req.get("name");

        if (accountManager == null) {
            sendJson(exchange, 500, Map.of("error", "AccountManager not configured"));
            return;
        }

        Account account = accountManager.register(email, password, name);
        Session session = accountManager.createSession(account);

        // Automatically create a default database for the new user!
        if (virtualDbManager != null) {
            virtualDbManager.createDatabase(account.getEmail(), "My First Cache");
        }

        sendJson(exchange, 201, Map.of(
                "success", true,
                "token", session.getToken(),
                "message", "Account registered successfully",
                "user", account.toSafeMap(),
                "session", session.toMap()
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleAuthLogin(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String email = (String) req.get("email");
        String password = (String) req.get("password");

        if (accountManager == null) {
            sendJson(exchange, 500, Map.of("error", "AccountManager not configured"));
            return;
        }

        Session session = accountManager.login(email, password);
        if (session == null) {
            sendJson(exchange, 401, Map.of("error", "Invalid email or password"));
            return;
        }

        Account account = accountManager.getAccountById(session.getUserId());
        sendJson(exchange, 200, Map.of(
                "success", true,
                "token", session.getToken(),
                "user", account.toSafeMap(),
                "session", session.toMap()
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleAuthGoogle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String email = (String) req.get("email");
        String name = (String) req.get("name");
        String avatar = (String) req.get("avatar");

        if (accountManager == null) {
            sendJson(exchange, 500, Map.of("error", "AccountManager not configured"));
            return;
        }

        if (email == null || email.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Google email is required"));
            return;
        }

        Session session = accountManager.loginWithGoogle(email, name, avatar);
        Account account = accountManager.getAccountById(session.getUserId());

        // Provision a database if the user has none
        if (virtualDbManager != null && virtualDbManager.listDatabasesForUser(account.getEmail()).isEmpty()) {
            virtualDbManager.createDatabase(account.getEmail(), "Google Cloud Cache");
        }

        sendJson(exchange, 200, Map.of(
                "success", true,
                "token", session.getToken(),
                "message", "Authenticated via Google Sign-In",
                "user", account.toSafeMap(),
                "session", session.toMap()
        ));
    }

    private void handleAuthMe(HttpExchange exchange) throws IOException {
        Account user = resolveRequestingUser(exchange);
        if (user == null) {
            sendJson(exchange, 401, Map.of("authenticated", false, "error", "No active session"));
            return;
        }
        sendJson(exchange, 200, Map.of(
                "authenticated", true,
                "user", user.toSafeMap()
        ));
    }

    private void handleAuthLogout(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (accountManager != null && authHeader != null) {
            accountManager.logout(authHeader);
        }
        sendJson(exchange, 200, Map.of("success", true, "message", "Logged out successfully"));
    }

    private void handleAuthDemoUsers(HttpExchange exchange) throws IOException {
        Account user = resolveRequestingUser(exchange);
        if (user == null || !user.isAdmin()) {
            sendJson(exchange, 403, Map.of("error", "Admin privileges required", "users", List.of()));
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        if (accountManager != null) {
            for (Account acc : accountManager.listAccounts()) {
                list.add(acc.toSafeMap());
            }
        }
        sendJson(exchange, 200, Map.of("users", list));
    }

    // ==========================================
    // Multi-Database Creation & Sharing Handlers
    // ==========================================

    private void handleListDatabases(HttpExchange exchange) throws IOException {
        String host = resolveHost(exchange);
        Account user = resolveRequestingUser(exchange);

        if (user == null) {
            sendJson(exchange, 401, Map.of("error", "Unauthorized", "myDatabases", List.of(), "sharedWithMe", List.of(), "databases", List.of()));
            return;
        }
        String userEmail = user.getEmail();

        if (virtualDbManager == null) {
            sendJson(exchange, 200, Map.of("myDatabases", List.of(), "sharedWithMe", List.of(), "databases", List.of()));
            return;
        }

        Map<String, List<DatabaseInstance>> cat = virtualDbManager.getCategorizedDatabases(userEmail);
        List<Map<String, Object>> myDbs = cat.get("myDatabases").stream().map(d -> d.toMap(host, redisPort, webPort, userEmail)).toList();
        List<Map<String, Object>> shared = cat.get("sharedWithMe").stream().map(d -> d.toMap(host, redisPort, webPort, userEmail)).toList();
        List<Map<String, Object>> allList = user.isAdmin() ? virtualDbManager.listDatabases() : myDbs;

        sendJson(exchange, 200, Map.of(
                "user", user.toSafeMap(),
                "myDatabases", myDbs,
                "sharedWithMe", shared,
                "databases", allList
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleCreateDatabase(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String name = (String) req.get("name");
        String customId = (String) req.get("id");
        String customPass = (String) req.get("password");

        if (name == null || name.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Database name is required"));
            return;
        }

        Account user = resolveRequestingUser(exchange);
        if (user == null) {
            sendJson(exchange, 401, Map.of("error", "Sign in required to create databases"));
            return;
        }
        String ownerEmail = user.getEmail();

        DatabaseInstance db = virtualDbManager.createDatabase(ownerEmail, name, customId, customPass);
        String host = resolveHost(exchange);

        sendJson(exchange, 201, Map.of(
                "success", true,
                "message", "Database '" + db.getName() + "' created successfully",
                "database", db.toMap(host, redisPort, webPort, ownerEmail)
        ));
    }

    private void handleDeleteDatabase(HttpExchange exchange, String dbId) throws IOException {
        if (dbId == null || dbId.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Database ID is required"));
            return;
        }
        Account user = resolveRequestingUser(exchange);
        String userEmail = user != null ? user.getEmail() : null;

        try {
            boolean deleted = virtualDbManager.deleteDatabase(dbId, userEmail);
            if (deleted) {
                sendJson(exchange, 200, Map.of("success", true, "message", "Database deleted successfully"));
            } else {
                sendJson(exchange, 404, Map.of("error", "Database not found"));
            }
        } catch (SecurityException se) {
            sendJson(exchange, 403, Map.of("error", se.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void handleShareDatabase(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String dbId = (String) (req.get("dbId") != null ? req.get("dbId") : req.get("id"));
        String targetEmail = (String) (req.get("targetEmail") != null ? req.get("targetEmail") : req.get("email"));
        String role = (String) req.getOrDefault("role", "EDITOR");

        if (dbId == null || targetEmail == null || targetEmail.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "Both database 'id' and 'email' are required"));
            return;
        }

        Account user = resolveRequestingUser(exchange);
        String requesterEmail = user != null ? user.getEmail() : null;

        virtualDbManager.shareDatabase(dbId, requesterEmail, targetEmail, role);
        DatabaseInstance db = virtualDbManager.getDatabase(dbId);
        String host = resolveHost(exchange);

        sendJson(exchange, 200, Map.of(
                "success", true,
                "message", "Database '" + db.getName() + "' shared with " + targetEmail + " as " + role,
                "database", db.toMap(host, redisPort, webPort, requesterEmail)
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleUnshareDatabase(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String dbId = (String) (req.get("dbId") != null ? req.get("dbId") : req.get("id"));
        String targetEmail = (String) (req.get("targetEmail") != null ? req.get("targetEmail") : req.get("email"));

        if (dbId == null || targetEmail == null) {
            sendJson(exchange, 400, Map.of("error", "Both database 'id' and 'email' are required"));
            return;
        }

        Account user = resolveRequestingUser(exchange);
        String requesterEmail = user != null ? user.getEmail() : null;

        virtualDbManager.unshareDatabase(dbId, requesterEmail, targetEmail);
        sendJson(exchange, 200, Map.of("success", true, "message", "Collaborator removed successfully"));
    }

    @SuppressWarnings("unchecked")
    private void handleShareLink(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String dbId = (String) (req.get("dbId") != null ? req.get("dbId") : req.get("id"));
        Boolean enabled = (Boolean) req.getOrDefault("enabled", true);
        String role = (String) req.getOrDefault("role", "VIEWER");

        DatabaseInstance db = virtualDbManager.getDatabase(dbId);
        if (db == null) {
            sendJson(exchange, 404, Map.of("error", "Database not found"));
            return;
        }

        Account user = resolveRequestingUser(exchange);
        if (user != null && !db.canAdmin(user.getEmail())) {
            sendJson(exchange, 403, Map.of("error", "Only the owner can configure share links"));
            return;
        }

        db.setShareLinkEnabled(enabled, role);
        String host = resolveHost(exchange);

        sendJson(exchange, 200, Map.of(
                "success", true,
                "token", db.getShareLinkToken(),
                "shareLinkEnabled", db.isShareLinkEnabled(),
                "shareLinkToken", db.getShareLinkToken(),
                "shareLinkUrl", "http://" + host + ":" + webPort + "/#join=" + db.getShareLinkToken()
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleJoinShareLink(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String token = (String) req.get("token");

        Account user = resolveRequestingUser(exchange);
        if (user == null) {
            sendJson(exchange, 401, Map.of("error", "Please sign in before joining a shared database"));
            return;
        }

        DatabaseInstance db = virtualDbManager.joinByShareLink(token, user.getEmail());
        if (db == null) {
            sendJson(exchange, 404, Map.of("error", "Invalid or expired share link"));
            return;
        }

        String host = resolveHost(exchange);
        sendJson(exchange, 200, Map.of(
                "success", true,
                "message", "Successfully joined database '" + db.getName() + "'",
                "database", db.toMap(host, redisPort, webPort, user.getEmail())
        ));
    }

    // ==========================================
    // Helpers & Permission Enforcement
    // ==========================================

    private Account resolveRequestingUser(HttpExchange exchange) {
        if (accountManager == null) return null;

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && !auth.isBlank()) {
            return accountManager.getAccountForSession(auth);
        }

        Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
        if (qp.containsKey("session")) {
            return accountManager.getAccountForSession(qp.get("session"));
        }
        if (qp.containsKey("userEmail")) {
            return accountManager.getAccountByEmail(qp.get("userEmail"));
        }

        return null;
    }

    private void checkWritePermission(String dbId, Account user) {
        if (dbId == null || dbId.isBlank() || dbId.equals("default") || dbId.equals("db0")) return;
        if (virtualDbManager == null || user == null) return;

        DatabaseInstance db = virtualDbManager.getDatabase(dbId);
        if (db != null && !db.canWrite(user.getEmail())) {
            throw new SecurityException("Permission Denied: You have VIEWER (read-only) permissions on database '" + db.getName() + "'");
        }
    }

    private StorageEngine resolveStorage(Map<String, String> queryParams) {
        if (virtualDbManager == null) return storage;
        String db = queryParams.get("db");
        if (db != null && !db.isBlank()) {
            return virtualDbManager.getStorageByDbName(db);
        }
        String tenant = queryParams.get("tenant");
        if (tenant != null && !tenant.isBlank()) {
            return virtualDbManager.getStorageForUser(tenant);
        }
        return storage;
    }

    private StorageEngine resolveStorage(Map<String, Object> req, Map<String, String> queryParams) {
        if (virtualDbManager == null) return storage;
        if (req != null) {
            if (req.containsKey("db") && req.get("db") != null) {
                return virtualDbManager.getStorageByDbName(String.valueOf(req.get("db")));
            }
            if (req.containsKey("tenant") && req.get("tenant") != null) {
                return virtualDbManager.getStorageForUser(String.valueOf(req.get("tenant")));
            }
        }
        return resolveStorage(queryParams);
    }

    private String getTargetDbId(Map<String, Object> req, Map<String, String> qp) {
        if (req != null && req.containsKey("db") && req.get("db") != null) {
            return String.valueOf(req.get("db"));
        }
        return qp.get("db");
    }

    private String getActiveDbName(Map<String, String> qp) {
        if (qp.containsKey("db") && !qp.get("db").isBlank()) {
            String dbId = qp.get("db");
            if (virtualDbManager != null) {
                DatabaseInstance db = virtualDbManager.getDatabase(dbId);
                if (db != null) return db.getName() + " (" + db.getId() + ")";
            }
            return dbId;
        }
        return "db0";
    }

    // ==========================================
    // Core Redis Data & Telemetry Handlers
    // ==========================================

    private void handleStats(HttpExchange exchange) throws IOException {
        Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
        Account user = resolveRequestingUser(exchange);
        StorageEngine target = resolveStorage(qp);
        Map<String, Object> stats = target.getMetrics().toMap(target.dbSize());
        stats.put("activeDb", getActiveDbName(qp));

        List<Map<String, Object>> dbList = new ArrayList<>();
        if (virtualDbManager != null && user != null) {
            if (user.isAdmin()) {
                dbList = virtualDbManager.listDatabases();
            } else {
                var cat = virtualDbManager.getCategorizedDatabases(user.getEmail());
                for (DatabaseInstance db : cat.get("myDatabases")) {
                    dbList.add(Map.of("id", db.getId(), "name", db.getName(), "owner", db.getOwnerEmail(), "keys", db.getStorage().dbSize()));
                }
                for (DatabaseInstance db : cat.get("sharedWithMe")) {
                    dbList.add(Map.of("id", db.getId(), "name", db.getName(), "owner", db.getOwnerEmail(), "keys", db.getStorage().dbSize()));
                }
            }
        }
        stats.put("databases", dbList);
        sendJson(exchange, 200, stats);
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

        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(request, queryParams);
        checkWritePermission(targetDbId, user);

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
        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(null, queryParams);
        checkWritePermission(targetDbId, user);

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

        String cmdName = tokens.get(0).toUpperCase();
        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(req, queryParams);

        // Check if command is mutating on a read-only database
        if (!User.isReadOnlyCommand(cmdName)) {
            checkWritePermission(targetDbId, user);
        }

        StorageEngine target = resolveStorage(req, queryParams);
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

        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(req, queryParams);
        checkWritePermission(targetDbId, user);

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

        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(req, queryParams);
        checkWritePermission(targetDbId, user);

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
        Account user = resolveRequestingUser(exchange);
        String targetDbId = getTargetDbId(null, queryParams);
        checkWritePermission(targetDbId, user);

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

    private void handleNetworkInfo(HttpExchange exchange) throws IOException {
        String reqHost = exchange.getRequestHeaders().getFirst("Host");
        if (reqHost != null && reqHost.contains(":")) {
            reqHost = reqHost.substring(0, reqHost.indexOf(':'));
        }
        String fwdHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (fwdHost != null && fwdHost.contains(":")) {
            fwdHost = fwdHost.substring(0, fwdHost.indexOf(':'));
        }

        String clientIp = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        }
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null)
                    ? exchange.getRemoteAddress().getAddress().getHostAddress()
                    : "127.0.0.1";
        } else if (clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }
        if (clientIp.startsWith("/")) clientIp = clientIp.substring(1);
        if (clientIp.startsWith("::ffff:")) clientIp = clientIp.substring(7);

        String publicHost = (config != null) ? config.getPublicHost() : null;
        String vpcIp = (config != null) ? config.getVpcPrivateIp() : com.myredis.config.ServerConfig.detectVpcPrivateIp();

        String effectivePublic = publicHost;
        if (effectivePublic == null || effectivePublic.isBlank()) {
            if (fwdHost != null && !fwdHost.isBlank()) {
                effectivePublic = fwdHost;
            } else if (reqHost != null && !reqHost.isBlank() && !reqHost.equalsIgnoreCase("localhost") && !reqHost.equals("127.0.0.1")) {
                effectivePublic = reqHost;
            } else {
                effectivePublic = vpcIp;
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("publicHost", effectivePublic);
        resp.put("vpcPrivateIp", vpcIp);
        resp.put("clientIp", clientIp);
        resp.put("requestHost", reqHost != null ? reqHost : "localhost");
        resp.put("redisPort", redisPort);
        resp.put("webPort", webPort);

        List<Map<String, String>> routes = List.of(
                Map.of("id", "public", "label", "Public IP / Domain", "host", effectivePublic, "desc", "Outer apps, Vercel, Lambda, external cloud"),
                Map.of("id", "vpc", "label", "VPC / Private Network", "host", vpcIp, "desc", "AWS/GCP VPC, Docker network, low-latency private interconnect"),
                Map.of("id", "custom", "label", "Custom Host / Domain", "host", "", "desc", "Custom domain, CNAME, Load Balancer or reverse proxy"),
                Map.of("id", "localhost", "label", "Localhost (127.0.0.1)", "host", "127.0.0.1", "desc", "Local machine, sidecar or dev testing")
        );
        resp.put("routingOptions", routes);

        sendJson(exchange, 200, resp);
    }

    @SuppressWarnings("unchecked")
    private void handleDatabaseIpRouting(HttpExchange exchange) throws IOException {
        Account user = resolveRequestingUser(exchange);
        if (user == null) {
            sendJson(exchange, 401, Map.of("error", "Sign in required to configure IP routing"));
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String dbId = (String) req.get("databaseId");
        if (dbId == null || dbId.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "databaseId is required"));
            return;
        }

        DatabaseInstance db = (virtualDbManager != null) ? virtualDbManager.getDatabase(dbId) : null;
        if (db == null) {
            sendJson(exchange, 404, Map.of("error", "Database not found: " + dbId));
            return;
        }

        if (!user.isAdmin() && !db.canWrite(user.getEmail())) {
            sendJson(exchange, 403, Map.of("error", "You do not have permission to modify IP routing for this database"));
            return;
        }

        Object ipsObj = req.get("allowedIps");
        List<String> ips = new ArrayList<>();
        if (ipsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    ips.add(String.valueOf(o).trim());
                }
            }
        } else if (ipsObj instanceof String s) {
            for (String part : s.split("[,;\\s]+")) {
                if (!part.isBlank()) ips.add(part.trim());
            }
        }

        db.setAllowedIps(ips);
        if (virtualDbManager != null) {
            virtualDbManager.saveMetadata();
        }

        String host = resolveHost(exchange);
        sendJson(exchange, 200, Map.of(
                "success", true,
                "message", "Database IP routing rules updated successfully",
                "database", db.toMap(host, redisPort, webPort, user.getEmail()),
                "allowedIps", db.getAllowedIps(),
                "publicAccess", db.isPublicAccess()
        ));
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
