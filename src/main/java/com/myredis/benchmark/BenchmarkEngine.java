package com.myredis.benchmark;

import com.myredis.commands.CommandRegistry;
import com.myredis.storage.StorageEngine;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Built-in high-throughput stress testing and benchmark engine.
 * Leverages Java 25 Virtual Threads to simulate massive concurrent client traffic.
 */
public class BenchmarkEngine {

    public record BenchmarkResult(
            int totalOperations,
            int concurrency,
            long durationMillis,
            double opsPerSec,
            double p50Millis,
            double p90Millis,
            double p99Millis,
            double minMillis,
            double maxMillis,
            int errorCount,
            Map<String, Integer> commandBreakdown
    ) {}

    public static BenchmarkResult runBenchmark(
            StorageEngine storage,
            CommandRegistry registry,
            int totalOperations,
            int concurrency
    ) throws InterruptedException {

        if (totalOperations <= 0) totalOperations = 10000;
        if (concurrency <= 0) concurrency = 50;

        // Pre-populate some keys so GET operations hit real data
        for (int i = 0; i < 500; i++) {
            storage.set("bench:init:" + i, "val_" + i, null, false, false);
        }

        int opsPerThread = totalOperations / concurrency;
        int remaining = totalOperations % concurrency;

        // Thread-safe latency collector (in microseconds)
        long[] latenciesNanos = new long[totalOperations];
        AtomicInteger latencyIndex = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        Map<String, AtomicInteger> cmdCounts = new ConcurrentHashMap<>();
        cmdCounts.put("SET", new AtomicInteger());
        cmdCounts.put("GET", new AtomicInteger());
        cmdCounts.put("INCR", new AtomicInteger());
        cmdCounts.put("LPUSH", new AtomicInteger());

        long startTimeNanos = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(concurrency);

            for (int t = 0; t < concurrency; t++) {
                final int opsToRun = opsPerThread + (t == 0 ? remaining : 0);
                final int threadId = t;

                futures.add(executor.submit(() -> {
                    var random = ThreadLocalRandom.current();

                    for (int i = 0; i < opsToRun; i++) {
                        int dice = random.nextInt(100);
                        List<String> cmd;

                        if (dice < 45) { // 45% GET
                            cmd = List.of("GET", "bench:init:" + random.nextInt(500));
                            cmdCounts.get("GET").incrementAndGet();
                        } else if (dice < 80) { // 35% SET
                            cmd = List.of("SET", "bench:key:" + threadId + ":" + i, "data_" + i);
                            cmdCounts.get("SET").incrementAndGet();
                        } else if (dice < 90) { // 10% INCR
                            cmd = List.of("INCR", "bench:counter:" + threadId);
                            cmdCounts.get("INCR").incrementAndGet();
                        } else { // 10% LPUSH
                            cmd = List.of("LPUSH", "bench:queue:" + threadId, "item_" + i);
                            cmdCounts.get("LPUSH").incrementAndGet();
                        }

                        long opStart = System.nanoTime();
                        try {
                            registry.execute(cmd, storage);
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                        long opElapsed = System.nanoTime() - opStart;

                        int idx = latencyIndex.getAndIncrement();
                        if (idx < latenciesNanos.length) {
                            latenciesNanos[idx] = opElapsed;
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ignored) {}
            }
        }

        long totalDurationNanos = System.nanoTime() - startTimeNanos;
        long totalDurationMillis = Math.max(1, totalDurationNanos / 1_000_000);
        double opsPerSec = ((double) totalOperations / totalDurationNanos) * 1_000_000_000.0;

        // Sort latencies to compute percentiles
        Arrays.sort(latenciesNanos);

        double minMs = latenciesNanos[0] / 1_000_000.0;
        double maxMs = latenciesNanos[latenciesNanos.length - 1] / 1_000_000.0;
        double p50Ms = latenciesNanos[(int) (latenciesNanos.length * 0.50)] / 1_000_000.0;
        double p90Ms = latenciesNanos[(int) (latenciesNanos.length * 0.90)] / 1_000_000.0;
        double p99Ms = latenciesNanos[(int) (latenciesNanos.length * 0.99)] / 1_000_000.0;

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        cmdCounts.forEach((k, v) -> breakdown.put(k, v.get()));

        return new BenchmarkResult(
                totalOperations,
                concurrency,
                totalDurationMillis,
                Math.round(opsPerSec * 100.0) / 100.0,
                Math.round(p50Ms * 1000.0) / 1000.0,
                Math.round(p90Ms * 1000.0) / 1000.0,
                Math.round(p99Ms * 1000.0) / 1000.0,
                Math.round(minMs * 1000.0) / 1000.0,
                Math.round(maxMs * 1000.0) / 1000.0,
                errorCount.get(),
                breakdown
        );
    }
}
