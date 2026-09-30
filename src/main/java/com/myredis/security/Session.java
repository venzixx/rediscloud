package com.myredis.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an authenticated session for a user accessing the Redis Web Console / REST API.
 */
public class Session {

    private final String token;
    private final String userId;
    private final String email;
    private final long createdAtMillis;
    private final long expiresAtMillis;

    public Session(String userId, String email, long durationMillis) {
        this("ses_" + UUID.randomUUID().toString().replace("-", ""), userId, email,
                System.currentTimeMillis(), System.currentTimeMillis() + durationMillis);
    }

    public Session(String token, String userId, String email, long createdAtMillis, long expiresAtMillis) {
        this.token = token;
        this.userId = userId;
        this.email = email;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAtMillis;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("token", token);
        map.put("userId", userId);
        map.put("email", email);
        map.put("expiresAt", expiresAtMillis);
        return map;
    }
}
