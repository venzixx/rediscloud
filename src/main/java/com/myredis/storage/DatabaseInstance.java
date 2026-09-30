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

    // IP Routing & CIDR Firewall Allowlist (e.g. 0.0.0.0/0 for public, 10.0.0.0/16 for VPC)
    private final Set<String> allowedIps = ConcurrentHashMap.newKeySet();

    private String shareLinkToken;
    private boolean shareLinkEnabled = false;
    private String shareLinkRole = "VIEWER";

    public DatabaseInstance(String id, String name, String ownerEmail, String password, String apiToken, StorageEngine storage, long createdAtMillis) {
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
        this.createdAtMillis = createdAtMillis > 0 ? createdAtMillis : System.currentTimeMillis();
        this.shareLinkToken = "lnk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.allowedIps.add("0.0.0.0/0"); // Default: open public access for outer apps (Vercel, external cloud)

        // Owner has full OWNER rights
        if (!this.ownerEmail.isBlank()) {
            collaborators.put(this.ownerEmail, "OWNER");
        }
    }

    public DatabaseInstance(String id, String name, String ownerEmail, String password, String apiToken, StorageEngine storage) {
        this(id, name, ownerEmail, password, apiToken, storage, System.currentTimeMillis());
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

    public Set<String> getAllowedIps() {
        return Collections.unmodifiableSet(allowedIps);
    }

    public void setAllowedIps(Collection<String> ips) {
        allowedIps.clear();
        if (ips == null || ips.isEmpty()) {
            allowedIps.add("0.0.0.0/0");
        } else {
            for (String ip : ips) {
                if (ip != null && !ip.isBlank()) {
                    allowedIps.add(ip.trim());
                }
            }
            if (allowedIps.isEmpty()) {
                allowedIps.add("0.0.0.0/0");
            }
        }
    }

    public void addAllowedIp(String ipOrCidr) {
        if (ipOrCidr != null && !ipOrCidr.isBlank()) {
            allowedIps.add(ipOrCidr.trim());
        }
    }

    public void removeAllowedIp(String ipOrCidr) {
        if (ipOrCidr != null) {
            allowedIps.remove(ipOrCidr.trim());
            if (allowedIps.isEmpty()) {
                allowedIps.add("0.0.0.0/0");
            }
        }
    }

    public boolean isPublicAccess() {
        return allowedIps.contains("0.0.0.0/0") || allowedIps.contains("*") || allowedIps.contains("all");
    }

    public boolean isIpAllowed(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        String cleanIp = clientIp.trim();
        if (cleanIp.startsWith("/")) cleanIp = cleanIp.substring(1);
        if (cleanIp.startsWith("::ffff:")) {
            cleanIp = cleanIp.substring(7);
        }
        if ("127.0.0.1".equals(cleanIp) || "0:0:0:0:0:0:0:1".equals(cleanIp) || "::1".equals(cleanIp) || "localhost".equalsIgnoreCase(cleanIp)) {
            return true;
        }
        if (isPublicAccess()) {
            return true;
        }
        for (String rule : allowedIps) {
            if ("0.0.0.0/0".equals(rule) || "*".equals(rule) || "all".equalsIgnoreCase(rule)) {
                return true;
            }
            if (rule.equalsIgnoreCase(cleanIp)) {
                return true;
            }
            if (rule.contains("/")) {
                if (matchesCidr(cleanIp, rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;
            long ipNum = ipv4ToLong(ip);
            long netNum = ipv4ToLong(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) return false;
            long mask = prefix == 0 ? 0L : (-1L << (32 - prefix)) & 0xFFFFFFFFL;
            return (ipNum & mask) == (netNum & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException("Invalid IPv4: " + ip);
        long res = 0;
        for (int i = 0; i < 4; i++) {
            long oct = Long.parseLong(octets[i]);
            if (oct < 0 || oct > 255) throw new IllegalArgumentException("Invalid IPv4 octet: " + oct);
            res |= (oct << ((3 - i) * 8));
        }
        return res & 0xFFFFFFFFL;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("ownerEmail", ownerEmail);
        map.put("password", password);
        map.put("apiToken", apiToken);
        map.put("createdAt", createdAtMillis);
        map.put("shareLinkToken", shareLinkToken);
        map.put("shareLinkEnabled", shareLinkEnabled);
        map.put("shareLinkRole", shareLinkRole);
        map.put("collaborators", new HashMap<>(collaborators));
        map.put("allowedIps", new ArrayList<>(allowedIps));
        return map;
    }

    @SuppressWarnings("unchecked")
    public static DatabaseInstance fromMetadataMap(Map<String, Object> map, StorageEngine storage) {
        if (map == null) return null;
        String id = (String) map.get("id");
        String name = (String) map.get("name");
        String ownerEmail = (String) map.get("ownerEmail");
        String password = (String) map.get("password");
        String apiToken = (String) map.get("apiToken");
        long createdAt = map.containsKey("createdAt") ? ((Number) map.get("createdAt")).longValue() : System.currentTimeMillis();

        DatabaseInstance db = new DatabaseInstance(id, name, ownerEmail, password, apiToken, storage, createdAt);
        if (map.containsKey("shareLinkToken")) {
            db.shareLinkToken = (String) map.get("shareLinkToken");
        }
        if (map.containsKey("shareLinkEnabled")) {
            db.shareLinkEnabled = Boolean.TRUE.equals(map.get("shareLinkEnabled"));
        }
        if (map.containsKey("shareLinkRole")) {
            db.shareLinkRole = (String) map.get("shareLinkRole");
        }
        if (map.get("collaborators") instanceof Map<?, ?> collabs) {
            collabs.forEach((k, v) -> db.addCollaborator(String.valueOf(k), String.valueOf(v)));
        }
        if (map.get("allowedIps") instanceof List<?> ips) {
            db.setAllowedIps(ips.stream().map(String::valueOf).toList());
        }
        return db;
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

        // IP Routing & CIDR Firewall Allowlist
        map.put("allowedIps", new ArrayList<>(allowedIps));
        map.put("isPublicAccess", isPublicAccess());

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
