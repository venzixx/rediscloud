package com.myredis.security;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a user/service account with ACL and Key-Level Security (RLS) rules.
 */
public class User {

    public enum Role {
        ADMIN,          // Full access to all commands and keys
        READ_WRITE,     // Read and write commands, subject to RLS key patterns
        READ_ONLY       // Read-only commands only, subject to RLS key patterns
    }

    private final String username;
    private final String password;
    private final String apiToken;
    private final Role role;
    private final List<String> keyPatterns;       // RLS: glob patterns allowed for this user
    private final List<Pattern> compiledPatterns;  // Compiled regexes for RLS matching
    private final Set<String> allowedCommands;    // If empty, allowed commands determined by role
    private final String virtualDbName;
    private final long createdAtMillis;

    public User(String username, String password, String apiToken, Role role, List<String> keyPatterns, Set<String> allowedCommands) {
        this(username, password, apiToken, role, keyPatterns, allowedCommands, "vdb_" + username.toLowerCase(), System.currentTimeMillis());
    }

    public User(String username, String password, String apiToken, Role role, List<String> keyPatterns, Set<String> allowedCommands, String virtualDbName, long createdAtMillis) {
        this.username = username;
        this.password = password;
        this.apiToken = apiToken;
        this.role = role;
        this.keyPatterns = keyPatterns != null ? keyPatterns : List.of("*");
        this.compiledPatterns = this.keyPatterns.stream().map(User::globToRegex).toList();
        this.allowedCommands = allowedCommands != null ? allowedCommands : Collections.emptySet();
        this.virtualDbName = virtualDbName != null ? virtualDbName : ("vdb_" + username.toLowerCase());
        this.createdAtMillis = createdAtMillis > 0 ? createdAtMillis : System.currentTimeMillis();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getApiToken() {
        return apiToken;
    }

    public Role getRole() {
        return role;
    }

    public List<String> getKeyPatterns() {
        return keyPatterns;
    }

    public String getVirtualDbName() {
        return virtualDbName;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public java.util.Map<String, Object> toMap(String host, int redisPort, int webPort) {
        String effectiveHost = (host == null || host.equals("0.0.0.0")) ? "localhost" : host;
        String stdUrl = "redis://" + username + ":" + password + "@" + effectiveHost + ":" + redisPort;
        String tokenUrl = "redis://:" + apiToken + "@" + effectiveHost + ":" + redisPort;
        String restUrl = "http://" + effectiveHost + ":" + webPort + "/v1";

        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("username", username);
        map.put("role", role.name());
        map.put("virtualDb", virtualDbName);
        map.put("apiToken", apiToken);
        map.put("password", password);
        map.put("keyPatterns", keyPatterns);
        map.put("createdAt", createdAtMillis);

        java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
        urls.put("redisUrl", stdUrl);
        urls.put("tokenUrl", tokenUrl);
        urls.put("prisma", stdUrl);
        urls.put("ioredis", stdUrl);
        urls.put("restUrl", restUrl);
        urls.put("cliCommand", "redis-cli -u " + stdUrl);
        urls.put("envSnippet", "REDIS_URL=\"" + stdUrl + "\"\nUPSTASH_REDIS_REST_URL=\"" + restUrl + "\"\nUPSTASH_REDIS_REST_TOKEN=\"" + apiToken + "\"");

        map.put("connectionUrls", urls);
        return map;
    }

    /**
     * Row/Key-Level Security (RLS) check: returns true if the key matches at least one allowed pattern.
     */
    public boolean isKeyAllowed(String key) {
        if (role == Role.ADMIN) return true;
        for (Pattern p : compiledPatterns) {
            if (p.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isCommandAllowed(String cmd) {
        if (role == Role.ADMIN) return true;

        String upper = cmd.toUpperCase();
        if (!allowedCommands.isEmpty()) {
            return allowedCommands.contains(upper);
        }

        if (role == Role.READ_ONLY) {
            return isReadOnlyCommand(upper);
        }

        // READ_WRITE cannot execute destructive administrative commands
        return !isRestrictedAdminCommand(upper);
    }

    public static boolean isReadOnlyCommand(String cmd) {
        return switch (cmd.toUpperCase()) {
            case "GET", "MGET", "STRLEN",
                 "HGET", "HGETALL", "HEXISTS", "HLEN", "HKEYS", "HVALS",
                 "LLEN", "LRANGE", "LINDEX",
                 "SMEMBERS", "SISMEMBER", "SCARD",
                 "EXISTS", "TTL", "PTTL", "TYPE", "KEYS",
                 "PING", "ECHO", "INFO", "DBSIZE", "TIME", "COMMAND" -> true;
            default -> false;
        };
    }

    public static boolean isRestrictedAdminCommand(String cmd) {
        return switch (cmd.toUpperCase()) {
            case "FLUSHDB", "FLUSHALL", "SHUTDOWN" -> true;
            default -> false;
        };
    }

    private static Pattern globToRegex(String glob) {
        if (glob == null || glob.equals("*")) {
            return Pattern.compile(".*");
        }
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); ++i) {
            final char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                case '.' -> out.append("\\.");
                case '\\' -> out.append("\\\\");
                case '[' -> out.append('[');
                case ']' -> out.append(']');
                default -> out.append(c);
            }
        }
        out.append('$');
        return Pattern.compile(out.toString());
    }
}
