package com.myredis.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access Control List (ACL) & Row/Key-Level Security (RLS) Engine.
 * Enforces role-based command restrictions and key pattern permissions across TCP & Cloud HTTP APIs.
 */
public class AclEngine {

    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, User> usersByToken = new ConcurrentHashMap<>();

    public AclEngine() {
        initDefaultUsers();
    }

    private void initDefaultUsers() {
        // 1. Full Administrator
        registerUser(new User(
                "admin",
                "admin123",
                "tok_admin_live_secret",
                User.Role.ADMIN,
                List.of("*"),
                Collections.emptySet()
        ));

        // 2. Read-Only Service Account
        registerUser(new User(
                "readonly",
                "readonly123",
                "tok_ro_public",
                User.Role.READ_ONLY,
                List.of("*"),
                Collections.emptySet()
        ));

        // 3. Multi-Tenant App Account with Key-Level Security (RLS)
        registerUser(new User(
                "tenant-app",
                "tenant123",
                "tok_app_tenant",
                User.Role.READ_WRITE,
                List.of("app:*", "tenant:*", "devops:*"),
                Collections.emptySet()
        ));
    }

    public void registerUser(User user) {
        usersByName.put(user.getUsername().toLowerCase(), user);
        usersByToken.put(user.getApiToken(), user);
    }

    public User getUser(String username) {
        if (username == null) return null;
        return usersByName.get(username.toLowerCase());
    }

    public User authenticate(String username, String password) {
        if (username == null || password == null) return null;
        User user = usersByName.get(username.toLowerCase());
        if (user != null && (user.getPassword().equals(password) || user.getApiToken().equals(password))) {
            return user;
        }
        return null;
    }

    public User authenticateSingleTokenOrPass(String tokenOrPass) {
        if (tokenOrPass == null || tokenOrPass.isBlank()) return null;
        String val = tokenOrPass.trim();

        // Check if formatted as username:password
        int colon = val.indexOf(':');
        if (colon > 0) {
            String u = val.substring(0, colon);
            String p = val.substring(colon + 1);
            User byPair = authenticate(u, p);
            if (byPair != null) return byPair;
        }

        // Direct token lookup
        User byToken = usersByToken.get(val);
        if (byToken != null) return byToken;

        // Check passwords of registered users
        for (User u : usersByName.values()) {
            if (u.getPassword().equals(val) || u.getApiToken().equals(val)) {
                return u;
            }
        }
        return null;
    }

    public User createTenantAccount(String username, String password, String customToken) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String cleanUser = username.trim().toLowerCase();
        if (usersByName.containsKey(cleanUser)) {
            throw new IllegalArgumentException("Account '" + cleanUser + "' already exists");
        }

        String token = (customToken != null && !customToken.isBlank())
                ? customToken.trim()
                : "red_api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String pass = (password != null && !password.isBlank())
                ? password.trim()
                : "sec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        User newUser = new User(
                cleanUser,
                pass,
                token,
                User.Role.READ_WRITE,
                List.of("*"),
                Collections.emptySet(),
                "vdb_" + cleanUser,
                System.currentTimeMillis()
        );

        registerUser(newUser);
        return newUser;
    }

    public User authenticateToken(String token) {
        if (token == null || token.isBlank()) return null;
        String cleanToken = token.trim();
        if (cleanToken.toLowerCase().startsWith("bearer ")) {
            cleanToken = cleanToken.substring(7).trim();
        }
        return usersByToken.get(cleanToken);
    }

    public User getDefaultAdminUser() {
        return usersByName.get("admin");
    }

    public List<Map<String, Object>> listUsersWithUrls(String host, int redisPort, int webPort) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : usersByName.values()) {
            list.add(u.toMap(host, redisPort, webPort));
        }
        list.sort(Comparator.comparing(m -> (String) m.get("username")));
        return list;
    }

    /**
     * Verifies that the user has permission to execute the given command
     * and that all target keys satisfy the user's RLS (Row/Key-Level Security) patterns.
     */
    public void verifyPermission(User user, List<String> commandArgs) throws SecurityException {
        if (user == null) {
            throw new SecurityException("NOAUTH Authentication required");
        }

        if (user.getRole() == User.Role.ADMIN) {
            return;
        }

        if (commandArgs == null || commandArgs.isEmpty()) {
            return;
        }

        String cmd = commandArgs.get(0).toUpperCase();

        // 1. ACL Check: Is the command allowed for this role?
        if (!user.isCommandAllowed(cmd)) {
            throw new SecurityException("NOPERM User '" + user.getUsername() + "' has no permissions for command '" + cmd + "'");
        }

        // 2. RLS Check: Are all keys referenced by this command within permitted patterns?
        List<String> targetKeys = extractTargetKeys(cmd, commandArgs);
        for (String key : targetKeys) {
            if (!user.isKeyAllowed(key)) {
                throw new SecurityException("NOPERM Key-level security (RLS) violation: user '"
                        + user.getUsername() + "' is not permitted to access key '" + key
                        + "' (allowed patterns: " + String.join(", ", user.getKeyPatterns()) + ")");
            }
        }
    }

    /**
     * Extracts keys from common Redis commands.
     */
    private List<String> extractTargetKeys(String cmd, List<String> args) {
        if (args.size() < 2) return Collections.emptyList();

        return switch (cmd) {
            case "MGET" -> args.subList(1, args.size());
            case "MSET" -> {
                List<String> keys = new ArrayList<>();
                for (int i = 1; i < args.size(); i += 2) {
                    keys.add(args.get(i));
                }
                yield keys;
            }
            case "DEL", "EXISTS" -> args.subList(1, args.size());
            case "RENAME" -> args.size() >= 3 ? List.of(args.get(1), args.get(2)) : List.of(args.get(1));
            // Commands where key is the first argument
            case "SET", "GET", "INCR", "DECR", "INCRBY", "DECRBY", "APPEND", "STRLEN",
                 "HSET", "HGET", "HDEL", "HGETALL", "HEXISTS", "HLEN", "HKEYS", "HVALS",
                 "LPUSH", "RPUSH", "LPOP", "RPOP", "LLEN", "LRANGE", "LINDEX",
                 "SADD", "SREM", "SMEMBERS", "SISMEMBER", "SCARD",
                 "EXPIRE", "PEXPIRE", "TTL", "PTTL", "PERSIST", "TYPE" -> List.of(args.get(1));
            default -> Collections.emptyList();
        };
    }

    public List<Map<String, Object>> listUsersSummary() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : usersByName.values()) {
            list.add(Map.of(
                    "username", u.getUsername(),
                    "role", u.getRole().name(),
                    "apiToken", u.getApiToken(),
                    "keyPatterns", u.getKeyPatterns()
            ));
        }
        list.sort(Comparator.comparing(m -> (String) m.get("username")));
        return list;
    }
}
