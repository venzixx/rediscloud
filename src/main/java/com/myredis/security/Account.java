package com.myredis.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a registered user account on the Cloud Redis platform
 * (authenticated via Email + Password or Google Sign-In).
 */
public class Account {

    private final String id;
    private final String email;
    private final String name;
    private final String passwordHash;
    private final String avatarUrl;
    private final String provider; // "email" or "google"
    private final long createdAtMillis;

    public Account(String id, String email, String name, String passwordHash, String avatarUrl, String provider, long createdAtMillis) {
        this.id = id;
        this.email = email != null ? email.trim().toLowerCase() : "";
        this.name = (name != null && !name.isBlank()) ? name.trim() : this.email;
        this.passwordHash = passwordHash;
        this.avatarUrl = avatarUrl != null ? avatarUrl : defaultAvatar(this.email, this.name);
        this.provider = provider != null ? provider : "email";
        this.createdAtMillis = createdAtMillis > 0 ? createdAtMillis : System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getProvider() {
        return provider;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public boolean verifyPassword(String rawPassword) {
        if (passwordHash == null || rawPassword == null) return false;
        String hash = hashPassword(rawPassword);
        return passwordHash.equals(hash) || passwordHash.equals(rawPassword);
    }

    public static String hashPassword(String rawPassword) {
        if (rawPassword == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(rawPassword.hashCode());
        }
    }

    public static String defaultAvatar(String email, String name) {
        String seed = (name != null && !name.isBlank()) ? name : (email != null ? email : "User");
        return "https://api.dicebear.com/7.x/identicon/svg?seed=" + seed.replace(" ", "_");
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("email", email);
        map.put("name", name);
        map.put("avatarUrl", avatarUrl);
        map.put("provider", provider);
        map.put("createdAt", createdAtMillis);
        return map;
    }
}
