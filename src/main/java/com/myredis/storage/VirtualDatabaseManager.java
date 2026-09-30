package com.myredis.storage;

import com.myredis.stats.ServerMetrics;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages virtualized, tenant-isolated in-memory Redis database instances.
 * Each tenant account gets a dedicated StorageEngine, isolating keys, expiry indexes, and metrics.
 */
public class VirtualDatabaseManager {

    private final Map<String, StorageEngine> databases = new ConcurrentHashMap<>();
    private final StorageEngine rootStorage;

    public VirtualDatabaseManager(StorageEngine rootStorage) {
        this.rootStorage = rootStorage;
        databases.put("default", rootStorage);
        databases.put("admin", rootStorage);
    }

    /**
     * Retrieves or creates the dedicated virtual StorageEngine for the given user/tenant.
     */
    public StorageEngine getStorageForUser(String username) {
        if (username == null || username.isBlank() || "admin".equalsIgnoreCase(username) || "default".equalsIgnoreCase(username)) {
            return rootStorage;
        }
        return databases.computeIfAbsent(username.toLowerCase(), u -> new StorageEngine(new ServerMetrics()));
    }

    /**
     * Resolves a virtual storage engine by database name (e.g. "db0", "vdb_alice", or "alice").
     */
    public StorageEngine getStorageByDbName(String dbName) {
        if (dbName == null || dbName.isBlank() || "default".equalsIgnoreCase(dbName) || "db0".equalsIgnoreCase(dbName) || "admin".equalsIgnoreCase(dbName)) {
            return rootStorage;
        }
        String clean = dbName.toLowerCase();
        if (clean.startsWith("vdb_")) {
            clean = clean.substring(4);
        }
        return databases.computeIfAbsent(clean, u -> new StorageEngine(new ServerMetrics()));
    }

    /**
     * Checks if a tenant database exists.
     */
    public boolean hasDatabase(String tenantOrDbName) {
        if (tenantOrDbName == null) return false;
        String clean = tenantOrDbName.toLowerCase().replace("vdb_", "");
        return databases.containsKey(clean) || "default".equals(clean) || "db0".equals(clean);
    }

    /**
     * Returns a summary list of all active virtual databases and their metrics.
     */
    public List<Map<String, Object>> listDatabases() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, StorageEngine> e : databases.entrySet()) {
            String tenant = e.getKey();
            StorageEngine se = e.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tenant", tenant);
            item.put("dbName", tenant.equals("default") ? "db0" : "vdb_" + tenant);
            item.put("keyCount", se.dbSize());
            item.put("memoryBytes", se.getMetrics().getUsedMemoryBytes());
            item.put("memoryHuman", se.getMetrics().getUsedMemoryHuman());
            item.put("isRoot", se == rootStorage);
            list.add(item);
        }
        list.sort(Comparator.comparing(m -> (String) m.get("tenant")));
        return list;
    }

    public Collection<StorageEngine> getAllStorageEngines() {
        return Collections.unmodifiableCollection(databases.values());
    }
}
