package com.myredis.storage;

import com.myredis.stats.ServerMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a user-created, shareable virtual Redis database with dedicated in-memory keyspace,
 * connection credentials (password & API token), and collaborator access controls.
 */
public class DatabaseInstance {

    private final String id;
    private String name;
    private final String ownerEmail;
    private final String password;
    private final String apiToken;
    private final long createdAtMillis;
    private final StorageEngine storage;

    // Collaborators: email (lowercase) -> Role ("OWNER", "EDITOR", "VIEWER")
    private final Map<String, String> collaborators = new ConcurrentHashMap<>();

    private String shareLinkToken;
    private boolean shareLinkEnabled = false;
    private String shareLinkRole = "VIEWER";

    public DatabaseInstance(String id, String name, String ownerEmail, String password, String apiToken, StorageEngine storage) {
        this.id = id != null ? id.trim() : ("db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        this.name = (name != null && !name.isBlank()) ? name.trim() : this.id;
        this.ownerEmail = ownerEmail != null ? ownerEmail.trim().toLowerCase() : "default";
        this.password = (password != null && !password.isBlank())
                ? password.trim()
                : "sec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.apiToken = (apiToken != null && !apiToken.isBlank())
                ? apiToken.trim()
                : "red_api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.storage = storage != null ? storage : new StorageEngine(new ServerMetrics());
        this.createdAtMillis = System.currentTimeMillis();
        this.shareLinkToken = "lnk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Owner has full OWNER rights
        if (!this.ownerEmail.isBlank()) {
            collaborators.put(this.ownerEmail, "OWNER");
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public String getPassword() {
        return password;
    }

    public String getApiToken() {
        return apiToken;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public StorageEngine getStorage() {
        return storage;
    }

    public Map<String, String> getCollaborators() {
        return Collections.unmodifiableMap(collaborators);
    }

    public String getShareLinkToken() {
        return shareLinkToken;
    }

    public boolean isShareLinkEnabled() {
        return shareLinkEnabled;
    }

    public void setShareLinkEnabled(boolean enabled, String defaultRole) {
        this.shareLinkEnabled = enabled;
        if (defaultRole != null) {
            this.shareLinkRole = defaultRole.toUpperCase();
        }
    }

    public String getShareLinkRole() {
        return shareLinkRole;
    }

    public void addCollaborator(String email, String role) {
        if (email == null || email.isBlank()) return;
        String clean = email.trim().toLowerCase();
        String cleanRole = (role != null) ? role.trim().toUpperCase() : "VIEWER";
        if (!cleanRole.equals("OWNER") && !cleanRole.equals("EDITOR") && !cleanRole.equals("VIEWER")) {
            cleanRole = "VIEWER";
        }
        collaborators.put(clean, cleanRole);
    }

    public void removeCollaborator(String email) {
        if (email == null) return;
        String clean = email.trim().toLowerCase();
        if (!clean.equalsIgnoreCase(ownerEmail)) {
            collaborators.remove(clean);
        }
    }

    public String getUserRole(String email) {
        if (email == null || email.isBlank()) return null;
        String clean = email.trim().toLowerCase();
        if (clean.equalsIgnoreCase(ownerEmail)) return "OWNER";
        return collaborators.get(clean);
    }

    public boolean canRead(String email) {
        String role = getUserRole(email);
        return role != null; // OWNER, EDITOR, or VIEWER
    }

    public boolean canWrite(String email) {
        String role = getUserRole(email);
        return "OWNER".equals(role) || "EDITOR".equals(role);
    }

    public boolean canAdmin(String email) {
        String role = getUserRole(email);
        return "OWNER".equals(role);
    }

    public Map<String, Object> toMap(String host, int redisPort, int webPort, String requesterEmail) {
        String effectiveHost = (host == null || host.equals("0.0.0.0")) ? "localhost" : host;
        String userRole = getUserRole(requesterEmail);
        if (userRole == null) userRole = "GUEST";

        String stdUrl = "redis://" + id + ":" + password + "@" + effectiveHost + ":" + redisPort;
        String tokenUrl = "redis://:" + apiToken + "@" + effectiveHost + ":" + redisPort;
        String restUrl = "http://" + effectiveHost + ":" + webPort + "/v1";

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("ownerEmail", ownerEmail);
        map.put("userRole", userRole);
        map.put("keyCount", storage.dbSize());
        map.put("memoryBytes", storage.getMetrics().getUsedMemoryBytes());
        map.put("memoryHuman", storage.getMetrics().getUsedMemoryHuman());
        map.put("createdAt", createdAtMillis);
        map.put("apiToken", apiToken);
        map.put("password", password);

        // Sharing info
        map.put("collaboratorsCount", collaborators.size());
        List<Map<String, String>> collabList = new ArrayList<>();
        for (Map.Entry<String, String> e : collaborators.entrySet()) {
            collabList.add(Map.of("email", e.getKey(), "role", e.getValue()));
        }
        map.put("collaborators", collabList);
        map.put("shareLinkEnabled", shareLinkEnabled);
        map.put("shareLinkToken", shareLinkToken);
        map.put("shareLinkRole", shareLinkRole);
        map.put("shareLinkUrl", "http://" + effectiveHost + ":" + webPort + "/#join=" + shareLinkToken);

        // Connection URLs
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("redisUrl", stdUrl);
        urls.put("tokenUrl", tokenUrl);
        urls.put("prisma", stdUrl);
        urls.put("ioredis", stdUrl);
        urls.put("restUrl", restUrl);
        urls.put("cliCommand", "redis-cli -u " + stdUrl);
        urls.put("envSnippet", "DATABASE_URL=\"" + stdUrl + "\"\nREDIS_URL=\"" + stdUrl + "\"\nUPSTASH_REDIS_REST_URL=\"" + restUrl + "\"\nUPSTASH_REDIS_REST_TOKEN=\"" + apiToken + "\"");

        map.put("connectionUrls", urls);
        return map;
    }
}
