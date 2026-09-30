package com.myredis.storage;

import com.myredis.stats.ServerMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages virtualized, tenant-isolated in-memory Redis database instances.
 * Supports multiple databases per user, email-based database sharing, and collaborator permissions.
 */
public class VirtualDatabaseManager {

    private final StorageEngine rootStorage;
    private final Map<String, StorageEngine> legacyDatabases = new ConcurrentHashMap<>();
    private final Map<String, DatabaseInstance> databasesById = new ConcurrentHashMap<>();

    public VirtualDatabaseManager(StorageEngine rootStorage) {
        this.rootStorage = rootStorage;
        legacyDatabases.put("default", rootStorage);
        legacyDatabases.put("admin", rootStorage);

        seedDemoDatabases();
    }

    private void seedDemoDatabases() {
        // Create demo databases for alex@rediscloud.dev and sarah@company.io
        DatabaseInstance prod = createDatabase("alex@rediscloud.dev", "Production Cache", "db_prod_cache", "sec_alex_prod_99");
        prod.getStorage().set("app:status", "online", null, false, false);
        prod.getStorage().set("cache:user:1", "{\"name\": \"Alice\", \"plan\": \"Pro\"}", null, false, false);

        DatabaseInstance sessions = createDatabase("alex@rediscloud.dev", "Session Store", "db_session_store", "sec_alex_sess_88");
        sessions.getStorage().set("session:usr_101", "active_jwt_token_alex", null, false, false);
        // Share Session Store with Sarah as EDITOR
        sessions.addCollaborator("sarah@company.io", "EDITOR");

        DatabaseInstance analytics = createDatabase("sarah@company.io", "Analytics Staging", "db_analytics_stg", "sec_sarah_ana_77");
        analytics.getStorage().set("event:pageview", "48201", null, false, false);
    }

    public synchronized DatabaseInstance createDatabase(String ownerEmail, String name) {
        String id = "db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return createDatabase(ownerEmail, name, id, null);
    }

    public synchronized DatabaseInstance createDatabase(String ownerEmail, String name, String customId, String customPassword) {
        String cleanOwner = (ownerEmail != null && !ownerEmail.isBlank()) ? ownerEmail.trim().toLowerCase() : "default";
        String id = (customId != null && !customId.isBlank()) ? customId.trim() : ("db_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        StorageEngine storage = new StorageEngine(new ServerMetrics());
        DatabaseInstance db = new DatabaseInstance(id, name, cleanOwner, customPassword, null, storage);
        databasesById.put(id.toLowerCase(), db);
        legacyDatabases.put(id.toLowerCase(), storage);
        return db;
    }

    public DatabaseInstance getDatabase(String dbId) {
        if (dbId == null) return null;
        return databasesById.get(dbId.trim().toLowerCase());
    }

    public boolean deleteDatabase(String dbId, String requesterEmail) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) return false;
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can delete this database");
        }
        databasesById.remove(db.getId().toLowerCase());
        legacyDatabases.remove(db.getId().toLowerCase());
        return true;
    }

    public List<DatabaseInstance> listDatabasesForUser(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return new ArrayList<>(databasesById.values());
        }
        String clean = userEmail.trim().toLowerCase();
        List<DatabaseInstance> list = new ArrayList<>();
        for (DatabaseInstance db : databasesById.values()) {
            if (db.canRead(clean)) {
                list.add(db);
            }
        }
        list.sort(Comparator.comparing(DatabaseInstance::getCreatedAtMillis).reversed());
        return list;
    }

    public Map<String, List<DatabaseInstance>> getCategorizedDatabases(String userEmail) {
        String clean = (userEmail != null) ? userEmail.trim().toLowerCase() : "";
        List<DatabaseInstance> owned = new ArrayList<>();
        List<DatabaseInstance> shared = new ArrayList<>();

        for (DatabaseInstance db : databasesById.values()) {
            if (clean.equalsIgnoreCase(db.getOwnerEmail())) {
                owned.add(db);
            } else if (db.canRead(clean)) {
                shared.add(db);
            }
        }
        owned.sort(Comparator.comparing(DatabaseInstance::getCreatedAtMillis).reversed());
        shared.sort(Comparator.comparing(DatabaseInstance::getCreatedAtMillis).reversed());

        return Map.of("myDatabases", owned, "sharedWithMe", shared);
    }

    public boolean shareDatabase(String dbId, String requesterEmail, String targetEmail, String role) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) {
            throw new IllegalArgumentException("Database not found: " + dbId);
        }
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can share this database");
        }
        db.addCollaborator(targetEmail, role);
        return true;
    }

    public boolean unshareDatabase(String dbId, String requesterEmail, String targetEmail) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) {
            throw new IllegalArgumentException("Database not found: " + dbId);
        }
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can manage collaborators");
        }
        db.removeCollaborator(targetEmail);
        return true;
    }

    public DatabaseInstance joinByShareLink(String shareToken, String userEmail) {
        if (shareToken == null || shareToken.isBlank() || userEmail == null || userEmail.isBlank()) return null;
        String cleanTok = shareToken.trim();
        for (DatabaseInstance db : databasesById.values()) {
            if (db.isShareLinkEnabled() && cleanTok.equals(db.getShareLinkToken())) {
                db.addCollaborator(userEmail, db.getShareLinkRole());
                return db;
            }
        }
        return null;
    }

    /**
     * Authenticates a Redis client connection on the TCP wire protocol (port 6379).
     * Matches either dbId:password, user:password, or single-token (token / password).
     */
    public DatabaseInstance resolveDatabaseByAuth(String userOrId, String passOrToken) {
        if (userOrId != null && passOrToken != null) {
            DatabaseInstance db = databasesById.get(userOrId.trim().toLowerCase());
            if (db != null && (db.getPassword().equals(passOrToken) || db.getApiToken().equals(passOrToken))) {
                return db;
            }
        }

        // Single argument token or password
        String single = (passOrToken != null) ? passOrToken : userOrId;
        if (single != null && !single.isBlank()) {
            String val = single.trim();
            for (DatabaseInstance db : databasesById.values()) {
                if (db.getApiToken().equals(val) || db.getPassword().equals(val)) {
                    return db;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a virtual storage engine by database name, database ID, or tenant name.
     */
    public StorageEngine getStorageByDbName(String dbName) {
        if (dbName == null || dbName.isBlank() || "default".equalsIgnoreCase(dbName) || "db0".equalsIgnoreCase(dbName) || "admin".equalsIgnoreCase(dbName)) {
            return rootStorage;
        }
        String clean = dbName.toLowerCase();
        DatabaseInstance db = databasesById.get(clean);
        if (db != null) {
            return db.getStorage();
        }
        if (clean.startsWith("vdb_")) {
            clean = clean.substring(4);
            DatabaseInstance db2 = databasesById.get(clean);
            if (db2 != null) return db2.getStorage();
        }
        return legacyDatabases.computeIfAbsent(clean, u -> new StorageEngine(new ServerMetrics()));
    }

    /**
     * Backward-compatible helper for user storage resolution.
     */
    public StorageEngine getStorageForUser(String username) {
        if (username == null || username.isBlank() || "admin".equalsIgnoreCase(username) || "default".equalsIgnoreCase(username)) {
            return rootStorage;
        }
        return getStorageByDbName(username);
    }

    public boolean hasDatabase(String tenantOrDbName) {
        if (tenantOrDbName == null) return false;
        String clean = tenantOrDbName.toLowerCase().replace("vdb_", "");
        return databasesById.containsKey(clean) || legacyDatabases.containsKey(clean) || "default".equals(clean) || "db0".equals(clean);
    }

    public List<Map<String, Object>> listDatabases() {
        List<Map<String, Object>> list = new ArrayList<>();

        // Root
        Map<String, Object> rootItem = new LinkedHashMap<>();
        rootItem.put("id", "db0");
        rootItem.put("name", "db0 (Default Root)");
        rootItem.put("tenant", "default");
        rootItem.put("dbName", "db0");
        rootItem.put("keyCount", rootStorage.dbSize());
        rootItem.put("memoryBytes", rootStorage.getMetrics().getUsedMemoryBytes());
        rootItem.put("memoryHuman", rootStorage.getMetrics().getUsedMemoryHuman());
        rootItem.put("isRoot", true);
        list.add(rootItem);

        // Created databases
        for (DatabaseInstance db : databasesById.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", db.getId());
            item.put("name", db.getName());
            item.put("ownerEmail", db.getOwnerEmail());
            item.put("tenant", db.getId());
            item.put("dbName", db.getId());
            item.put("keyCount", db.getStorage().dbSize());
            item.put("memoryBytes", db.getStorage().getMetrics().getUsedMemoryBytes());
            item.put("memoryHuman", db.getStorage().getMetrics().getUsedMemoryHuman());
            item.put("isRoot", false);
            list.add(item);
        }

        // Backward compatibility: Any legacy tenant databases created via getStorageForUser
        for (Map.Entry<String, StorageEngine> entry : legacyDatabases.entrySet()) {
            String key = entry.getKey();
            if ("default".equals(key) || "admin".equals(key) || databasesById.containsKey(key)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", key);
            item.put("name", key);
            item.put("ownerEmail", "default");
            item.put("tenant", key);
            item.put("dbName", key);
            item.put("keyCount", entry.getValue().dbSize());
            item.put("memoryBytes", entry.getValue().getMetrics().getUsedMemoryBytes());
            item.put("memoryHuman", entry.getValue().getMetrics().getUsedMemoryHuman());
            item.put("isRoot", false);
            list.add(item);
        }

        list.sort(Comparator.comparing(m -> (String) m.get("name")));
        return list;
    }

    public Collection<StorageEngine> getAllStorageEngines() {
        Set<StorageEngine> set = new HashSet<>(legacyDatabases.values());
        for (DatabaseInstance db : databasesById.values()) {
            set.add(db.getStorage());
        }
        set.add(rootStorage);
        return Collections.unmodifiableCollection(set);
    }
}
