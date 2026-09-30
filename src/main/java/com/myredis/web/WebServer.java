package com.myredis.web;

import com.myredis.commands.CommandRegistry;
import com.myredis.security.AclEngine;
import com.myredis.storage.StorageEngine;
import com.myredis.storage.VirtualDatabaseManager;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP Web Server running on Java Virtual Threads.
 * Hosts the management REST API and serves the single-page Web Dashboard.
 */
public class WebServer {

    private final String host;
    private final int port;
    private final int redisPort;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final AclEngine aclEngine;
    private final VirtualDatabaseManager virtualDbManager;
    private final com.myredis.security.AccountManager accountManager;
    private HttpServer server;

    public WebServer(String host, int port, int redisPort, StorageEngine storage,
                     CommandRegistry registry, AclEngine aclEngine, VirtualDatabaseManager virtualDbManager,
                     com.myredis.security.AccountManager accountManager) {
        this.host = host;
        this.port = port;
        this.redisPort = redisPort > 0 ? redisPort : 6379;
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.virtualDbManager = virtualDbManager;
        this.accountManager = accountManager;
    }

    public WebServer(String host, int port, int redisPort, StorageEngine storage,
                     CommandRegistry registry, AclEngine aclEngine, VirtualDatabaseManager virtualDbManager) {
        this(host, port, redisPort, storage, registry, aclEngine, virtualDbManager, null);
    }

    public WebServer(String host, int port, StorageEngine storage, CommandRegistry registry, AclEngine aclEngine) {
        this(host, port, 6379, storage, registry, aclEngine, null, null);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/v1/", new CloudApiHandler(storage, registry, aclEngine, virtualDbManager, redisPort, port));
        server.createContext("/api/", new ApiHandler(storage, registry, aclEngine, virtualDbManager, accountManager, redisPort, port));
        server.createContext("/", new StaticResourceHandler());

        server.start();
        String displayHost = (host.equals("0.0.0.0") ? "localhost" : host);
        System.out.println("=================================================");
        System.out.println("  * Web Studio:    http://" + displayHost + ":" + port);
        System.out.println("  * Cloud KV API:  http://" + displayHost + ":" + port + "/v1/");
        System.out.println("  * REST API:      http://" + displayHost + ":" + port + "/api/stats");
        System.out.println("=================================================");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return port;
    }
}
