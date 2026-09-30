package com.myredis.storage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background daemon that proactively sweeps and evicts expired keys.
 */
public class ExpiryEngine {

    private final StorageEngine storage;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public ExpiryEngine(StorageEngine storage) {
        this.storage = storage;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-expiry-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start(long periodMillis) {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.cleanExpiredKeys();
            } catch (Throwable t) {
                // Safeguard background loop
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdownNow();
    }
}
