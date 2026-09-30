package com.myredis;

import com.myredis.commands.CommandRegistry;
import com.myredis.config.ServerConfig;
import com.myredis.network.RedisServer;
import com.myredis.stats.ServerMetrics;
import com.myredis.storage.ExpiryEngine;
import com.myredis.storage.PersistenceEngine;
import com.myredis.storage.StorageEngine;
import com.myredis.web.WebServer;

public class Main {

    public static void main(String[] args) {
        printBanner();

        ServerConfig config = ServerConfig.load();

        ServerMetrics metrics = new ServerMetrics();
        StorageEngine storage = new StorageEngine(metrics);
        PersistenceEngine persistence = new PersistenceEngine(config.getAofPath(), config.isAofEnabled());
        CommandRegistry registry = new CommandRegistry(persistence);

        try {
            // 1. Replay recorded log first, then initialize append stream
            persistence.replay(cmdArgs -> registry.execute(cmdArgs, storage));
            persistence.init();

            // 2. Start proactive TTL sweeper
            ExpiryEngine expiryEngine = new ExpiryEngine(storage);
            expiryEngine.start(config.getExpiryIntervalMillis());

            // 3. Security & ACL Engine
            com.myredis.security.AclEngine aclEngine = new com.myredis.security.AclEngine();

            // 4. Start Redis TCP Server (port 6379)
            RedisServer redisServer = new RedisServer(config.getHost(), config.getRedisPort(), storage, registry, aclEngine);
            redisServer.start();

            // 5. Start Web Management Server (port 8080)
            WebServer webServer = new WebServer(config.getHost(), config.getWebPort(), storage, registry, aclEngine);
            webServer.start();

            // Graceful shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Shutdown] Shutting down Redis server and Web UI...");
                redisServer.stop();
                webServer.stop();
                expiryEngine.stop();
                persistence.close();
                System.out.println("[Shutdown] Completed clean shutdown.");
            }));

            // Keep main process running
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("[Fatal] Server failed to start: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printBanner() {
        System.out.println("""
                 ____          _ _         _                    
                |  _ \\ ___  __| (_)___    | | __ ___   ____ _   
                | |_) / _ \\/ _` | / __|   | |/ _` \\ \\ / / _` |  
                |  _ <  __/ (_| | \\__ \\_  | | (_| |\\ V / (_| |  
                |_| \\_\\___|\\__,_|_|___(_) |_|\\__,_| \\_/ \\__,_|  
                                                                
                >> Production In-Memory Data Store + Cloud Web Console
                >> Powered by Java 25 Virtual Threads (Project Loom)
                """);
    }
}
