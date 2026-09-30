package com.myredis.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user identities, email/password credentials, Google Sign-In,
 * active web sessions, and persistent storage for the Redis Cloud Platform.
 */
public class AccountManager {

    private final Map<String, Account> accountsByEmail = new ConcurrentHashMap<>();
    private final Map<String, Account> accountsById = new ConcurrentHashMap<>();
    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();

    private static final long DEFAULT_SESSION_DURATION = 14L * 24 * 3600 * 1000L; // 14 days
    private static final Path ACCOUNTS_FILE = Paths.get("data", "accounts.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean persistent;

    private static boolean isTestEnvironment() {
        return System.getProperty("surefire.test.class.path") != null ||
               System.getProperty("test.env") != null;
    }

    public AccountManager() {
        this(!isTestEnvironment());
    }

    public AccountManager(boolean persistent) {
        this.persistent = persistent;
        if (persistent) {
            loadFromDisk();
        } else {
            // In-memory test mode - ensure superadmin admin@gmail.com exists
            registerAdmin("admin@gmail.com", "admin123", "Super Admin");
        }
    }

    private synchronized void loadFromDisk() {
        try {
            File file = ACCOUNTS_FILE.toFile();
            if (file.exists() && file.length() > 0) {
                List<Map<String, Object>> list = mapper.readValue(file, new TypeReference<>() {});
                for (Map<String, Object> map : list) {
                    Account account = Account.fromMap(map);
                    if (account != null && !account.getEmail().isBlank()) {
                        accountsByEmail.put(account.getEmail(), account);
                        accountsById.put(account.getId(), account);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[AccountManager] Could not load accounts from " + ACCOUNTS_FILE + ": " + e.getMessage());
        }

        // Ensure superadmin admin@gmail.com exists
        if (!accountsByEmail.containsKey("admin@gmail.com")) {
            registerAdmin("admin@gmail.com", "admin123", "Super Admin");
        }
    }

    private synchronized void saveToDisk() {
        if (!persistent) return;
        try {
            Path parent = ACCOUNTS_FILE.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Account acc : accountsByEmail.values()) {
                list.add(acc.toMap());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(ACCOUNTS_FILE.toFile(), list);
        } catch (IOException e) {
            System.err.println("[AccountManager] Failed to persist accounts to disk: " + e.getMessage());
        }
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
        String role = cleanEmail.equalsIgnoreCase("admin@gmail.com") ? "admin" : "user";

        Account account = new Account(id, cleanEmail, displayName, passHash, avatarUrl, "email", role, System.currentTimeMillis());
        accountsByEmail.put(cleanEmail, account);
        accountsById.put(id, account);

        saveToDisk();
        return account;
    }

    public synchronized Account registerAdmin(String email, String rawPassword, String name) {
        String cleanEmail = email.trim().toLowerCase();
        String id = "usr_admin_01";
        String passHash = Account.hashPassword(rawPassword);
        Account account = new Account(id, cleanEmail, name, passHash, Account.defaultAvatar(cleanEmail, name), "email", "admin", System.currentTimeMillis());
        accountsByEmail.put(cleanEmail, account);
        accountsById.put(id, account);
        saveToDisk();
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
            String role = cleanEmail.equalsIgnoreCase("admin@gmail.com") ? "admin" : "user";
            account = new Account(id, cleanEmail, displayName, null, avatarUrl, "google", role, System.currentTimeMillis());
            accountsByEmail.put(cleanEmail, account);
            accountsById.put(id, account);
            saveToDisk();
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
            String clean = sessionToken.trim();
            if (clean.toLowerCase().startsWith("bearer ")) {
                clean = clean.substring(7).trim();
            }
            activeSessions.remove(clean);
        }
    }

    public List<Account> listAccounts() {
        return new ArrayList<>(accountsByEmail.values());
    }
}
