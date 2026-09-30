package com.myredis.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myredis.stats.ServerMetrics;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages virtualized, tenant-isolated in-memory Redis database instances.
 * Supports multiple databases per user, email-based database sharing, collaborator permissions,
 * and persistent storage across server restarts.
 */
public class VirtualDatabaseManager {

    private final StorageEngine rootStorage;
    private final Map<String, StorageEngine> legacyDatabases = new ConcurrentHashMap<>();
    private final Map<String, DatabaseInstance> databasesById = new ConcurrentHashMap<>();

    private static final Path DATABASES_FILE = Paths.get("data", "databases.json");
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean persistent;

    private static boolean isTestEnvironment() {
        return System.getProperty("surefire.test.class.path") != null ||
               System.getProperty("test.env") != null;
    }

    public VirtualDatabaseManager(StorageEngine rootStorage) {
        this(rootStorage, !isTestEnvironment());
    }

    public VirtualDatabaseManager(StorageEngine rootStorage, boolean persistent) {
        this.rootStorage = rootStorage;
        this.persistent = persistent;
        legacyDatabases.put("default", rootStorage);
        legacyDatabases.put("admin", rootStorage);

        if (persistent) {
            loadFromDisk();
        } else {
            // In-memory mode (tests)
            DatabaseInstance adminDb = createDatabase("admin@gmail.com", "Primary Production Cache", "db_primary_cache", "sec_admin_cache_99");
            adminDb.getStorage().set("system:welcome", "Welcome to Redis Cloud!", null, false, false);
            adminDb.getStorage().set("app:status", "online", null, false, false);
        }
    }

    private synchronized void loadFromDisk() {
        try {
            File file = DATABASES_FILE.toFile();
            if (file.exists() && file.length() > 0) {
                List<Map<String, Object>> list = mapper.readValue(file, new TypeReference<>() {});
                for (Map<String, Object> map : list) {
                    StorageEngine storage = new StorageEngine(new ServerMetrics());
                    DatabaseInstance db = DatabaseInstance.fromMetadataMap(map, storage);
                    if (db != null) {
                        databasesById.put(db.getId().toLowerCase(), db);
                        legacyDatabases.put(db.getId().toLowerCase(), storage);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[VirtualDatabaseManager] Could not load databases from " + DATABASES_FILE + ": " + e.getMessage());
        }

        // If no databases exist at all, create initial default database for admin@gmail.com
        if (databasesById.isEmpty()) {
            DatabaseInstance adminDb = createDatabase("admin@gmail.com", "Primary Production Cache", "db_primary_cache", "sec_admin_cache_99");
            adminDb.getStorage().set("system:welcome", "Welcome to Redis Cloud!", null, false, false);
            adminDb.getStorage().set("app:status", "online", null, false, false);
        }
    }

    private synchronized void saveToDisk() {
        if (!persistent) return;
        try {
            Path parent = DATABASES_FILE.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (DatabaseInstance db : databasesById.values()) {
                list.add(db.toMetadataMap());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(DATABASES_FILE.toFile(), list);
        } catch (IOException e) {
            System.err.println("[VirtualDatabaseManager] Failed to persist databases to disk: " + e.getMessage());
        }
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

        saveToDisk();
        return db;
    }

    public DatabaseInstance getDatabase(String dbId) {
        if (dbId == null) return null;
        return databasesById.get(dbId.trim().toLowerCase());
    }

    public synchronized boolean deleteDatabase(String dbId, String requesterEmail) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) return false;
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can delete this database");
        }
        databasesById.remove(db.getId().toLowerCase());
        legacyDatabases.remove(db.getId().toLowerCase());
        saveToDisk();
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

    public synchronized boolean shareDatabase(String dbId, String requesterEmail, String targetEmail, String role) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) {
            throw new IllegalArgumentException("Database not found: " + dbId);
        }
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can share this database");
        }
        db.addCollaborator(targetEmail, role);
        saveToDisk();
        return true;
    }

    public synchronized boolean unshareDatabase(String dbId, String requesterEmail, String targetEmail) {
        DatabaseInstance db = getDatabase(dbId);
        if (db == null) {
            throw new IllegalArgumentException("Database not found: " + dbId);
        }
        if (requesterEmail != null && !db.canAdmin(requesterEmail)) {
            throw new SecurityException("Only the database owner can manage collaborators");
        }
        db.removeCollaborator(targetEmail);
        saveToDisk();
        return true;
    }

    public synchronized DatabaseInstance joinByShareLink(String shareToken, String userEmail) {
        if (shareToken == null || shareToken.isBlank() || userEmail == null || userEmail.isBlank()) return null;
        String cleanTok = shareToken.trim();
        for (DatabaseInstance db : databasesById.values()) {
            if (db.isShareLinkEnabled() && cleanTok.equals(db.getShareLinkToken())) {
                db.addCollaborator(userEmail, db.getShareLinkRole());
                saveToDisk();
                return db;
            }
        }
        return null;
    }

    /**
     * Authenticates a Redis client connection on the TCP wire protocol (port 6379).
     */
    public DatabaseInstance resolveDatabaseByAuth(String userOrId, String passOrToken) {
        if (userOrId != null && passOrToken != null) {
            DatabaseInstance db = databasesById.get(userOrId.trim().toLowerCase());
            if (db != null && (db.getPassword().equals(passOrToken) || db.getApiToken().equals(passOrToken))) {
                return db;
            }
        }

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

        // Root db0
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
