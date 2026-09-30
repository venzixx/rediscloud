package com.myredis.storage;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Value wrapper holding the underlying data, type, and expiration timestamp.
 */
public class RedisEntry {

    private final DataType type;
    private Object value;
    private volatile long expiresAtMillis; // -1 for no expiration
    private final long createdAtMillis;

    public RedisEntry(DataType type, Object value, long expiresAtMillis) {
        this.type = type;
        this.value = value;
        this.expiresAtMillis = expiresAtMillis;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public RedisEntry(DataType type, Object value) {
        this(type, value, -1);
    }

    public DataType getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public void setExpiresAtMillis(long expiresAtMillis) {
        this.expiresAtMillis = expiresAtMillis;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean isExpired(long now) {
        return expiresAtMillis != -1 && now >= expiresAtMillis;
    }

    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    /**
     * TTL in seconds according to Redis spec:
     * -2 if key does not exist (or expired)
     * -1 if key exists but has no associated expire
     * >= 0 remaining time in seconds
     */
    public long getTtlSeconds(long now) {
        if (expiresAtMillis == -1) {
            return -1;
        }
        long diff = expiresAtMillis - now;
        if (diff <= 0) {
            return -2;
        }
        return (diff + 999) / 1000;
    }

    /**
     * PTTL in milliseconds.
     */
    public long getTtlMillis(long now) {
        if (expiresAtMillis == -1) {
            return -1;
        }
        long diff = expiresAtMillis - now;
        if (diff <= 0) {
            return -2;
        }
        return diff;
    }

    @SuppressWarnings("unchecked")
    public String asString() {
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    public List<String> asList() {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> asHash() {
        return (Map<String, String>) value;
    }

    @SuppressWarnings("unchecked")
    public Set<String> asSet() {
        return (Set<String>) value;
    }
}
