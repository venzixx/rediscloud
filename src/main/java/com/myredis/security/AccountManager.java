package com.myredis.security;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user identities, email/password credentials, Google Sign-In,
 * and active web sessions for the Redis Cloud Platform.
 */
public class AccountManager {

    private final Map<String, Account> accountsByEmail = new ConcurrentHashMap<>();
    private final Map<String, Account> accountsById = new ConcurrentHashMap<>();
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    private static final long DEFAULT_SESSION_DURATION = 14L * 24 * 3600 * 1000L; // 14 days

    public AccountManager() {
        seedDefaultAccounts();
    }

    private void seedDefaultAccounts() {
        // Pre-seed demo users for immediate zero-friction testing
        register("alex@rediscloud.dev", "redis123", "Alex Rivera (Lead Dev)", "https://api.dicebear.com/7.x/identicon/svg?seed=Alex");
        register("sarah@company.io", "redis123", "Sarah Chen (Product)", "https://api.dicebear.com/7.x/identicon/svg?seed=Sarah");
    }

    public synchronized Account register(String email, String rawPassword, String name, String avatarUrl) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address is required");
        }
        String cleanEmail = email.trim().toLowerCase();
        if (accountsByEmail.containsKey(cleanEmail)) {
            throw new IllegalArgumentException("Account with email '" + cleanEmail + "' already exists");
        }

        String id = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String passHash = (rawPassword != null && !rawPassword.isBlank()) ? Account.hashPassword(rawPassword) : null;
        String displayName = (name != null && !name.isBlank()) ? name.trim() : cleanEmail.split("@")[0];

        Account account = new Account(id, cleanEmail, displayName, passHash, avatarUrl, "email", System.currentTimeMillis());
        accountsByEmail.put(cleanEmail, account);
        accountsById.put(id, account);
        return account;
    }

    public synchronized Account register(String email, String rawPassword, String name) {
        return register(email, rawPassword, name, null);
    }

    public Session login(String email, String rawPassword) {
        if (email == null || rawPassword == null) return null;
        Account account = accountsByEmail.get(email.trim().toLowerCase());
        if (account == null || !account.verifyPassword(rawPassword)) {
            return null;
        }
        return createSession(account);
    }

    public synchronized Session loginWithGoogle(String email, String name, String avatarUrl) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Google account email is required");
        }
        String cleanEmail = email.trim().toLowerCase();
        Account account = accountsByEmail.get(cleanEmail);

        if (account == null) {
            String id = "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String displayName = (name != null && !name.isBlank()) ? name.trim() : cleanEmail.split("@")[0];
            account = new Account(id, cleanEmail, displayName, null, avatarUrl, "google", System.currentTimeMillis());
            accountsByEmail.put(cleanEmail, account);
            accountsById.put(id, account);
        }

        return createSession(account);
    }

    public Session createSession(Account account) {
        Session session = new Session(account.getId(), account.getEmail(), DEFAULT_SESSION_DURATION);
        activeSessions.put(session.getToken(), session);
        return session;
    }

    public Session getSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return null;
        String clean = sessionToken.trim();
        if (clean.toLowerCase().startsWith("bearer ")) {
            clean = clean.substring(7).trim();
        }
        Session session = activeSessions.get(clean);
        if (session != null) {
            if (session.isExpired()) {
                activeSessions.remove(clean);
                return null;
            }
            return session;
        }
        return null;
    }

    public Account getAccountForSession(String sessionToken) {
        Session session = getSession(sessionToken);
        if (session == null) return null;
        return accountsByEmail.get(session.getEmail());
    }

    public Account getAccountByEmail(String email) {
        if (email == null) return null;
        return accountsByEmail.get(email.trim().toLowerCase());
    }

    public Account getAccountById(String id) {
        if (id == null) return null;
        return accountsById.get(id.trim());
    }

    public void logout(String sessionToken) {
        if (sessionToken != null) {
            activeSessions.remove(sessionToken.trim());
        }
    }

    public List<Account> listAccounts() {
        return new ArrayList<>(accountsByEmail.values());
    }
}
