package com.myredis.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live server metrics for Redis INFO command and Web Dashboard.
 */
public class ServerMetrics {

    private final long startTimeMillis = System.currentTimeMillis();
    private final AtomicLong totalCommands = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong keyspaceHits = new AtomicLong();
    private final AtomicLong keyspaceMisses = new AtomicLong();
    private final AtomicLong expiredKeys = new AtomicLong();

    public void incrementCommands() {
        totalCommands.incrementAndGet();
    }

    public void clientConnected() {
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
    }

    public void clientDisconnected() {
        activeConnections.decrementAndGet();
    }

    public void recordHit() {
        keyspaceHits.incrementAndGet();
    }

    public void recordMiss() {
        keyspaceMisses.incrementAndGet();
    }

    public void recordExpiredKey() {
        expiredKeys.incrementAndGet();
    }

    public long getTotalCommands() {
        return totalCommands.get();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    public long getKeyspaceHits() {
        return keyspaceHits.get();
    }

    public long getKeyspaceMisses() {
        return keyspaceMisses.get();
    }

    public long getExpiredKeys() {
        return expiredKeys.get();
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000;
    }

    public long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public long getTotalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    public long getMaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    public Map<String, Object> toMap(int totalKeys) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", "1.0.0-java25");
        map.put("uptime_in_seconds", getUptimeSeconds());
        map.put("connected_clients", getActiveConnections());
        map.put("total_connections_received", getTotalConnections());
        map.put("total_commands_processed", getTotalCommands());
        map.put("total_keys", totalKeys);
        map.put("keyspace_hits", getKeyspaceHits());
        map.put("keyspace_misses", getKeyspaceMisses());
        map.put("expired_keys", getExpiredKeys());
        map.put("used_memory_bytes", getUsedMemory());
        map.put("total_memory_bytes", getTotalMemory());
        map.put("max_memory_bytes", getMaxMemory());
        map.put("used_memory_human", formatBytes(getUsedMemory()));
        return map;
    }

    public String toRedisInfo(int totalKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Server\r\n");
        sb.append("redis_version:7.0.0-myredis-java\r\n");
        sb.append("redis_mode:standalone\r\n");
        sb.append("os:").append(System.getProperty("os.name")).append("\r\n");
        sb.append("arch_bits:64\r\n");
        sb.append("java_version:").append(System.getProperty("java.version")).append("\r\n");
        sb.append("uptime_in_seconds:").append(getUptimeSeconds()).append("\r\n\r\n");

        sb.append("# Clients\r\n");
        sb.append("connected_clients:").append(getActiveConnections()).append("\r\n\r\n");

        sb.append("# Memory\r\n");
        sb.append("used_memory:").append(getUsedMemory()).append("\r\n");
        sb.append("used_memory_human:").append(formatBytes(getUsedMemory())).append("\r\n");
        sb.append("maxmemory:").append(getMaxMemory()).append("\r\n\r\n");

        sb.append("# Stats\r\n");
        sb.append("total_connections_received:").append(getTotalConnections()).append("\r\n");
        sb.append("total_commands_processed:").append(getTotalCommands()).append("\r\n");
        sb.append("keyspace_hits:").append(getKeyspaceHits()).append("\r\n");
        sb.append("keyspace_misses:").append(getKeyspaceMisses()).append("\r\n");
        sb.append("expired_keys:").append(getExpiredKeys()).append("\r\n\r\n");

        sb.append("# Keyspace\r\n");
        sb.append("db0:keys=").append(totalKeys).append(",expires=0,avg_ttl=0\r\n");

        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
