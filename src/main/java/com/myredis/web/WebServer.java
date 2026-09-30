package com.myredis.web;

import com.myredis.commands.CommandRegistry;
import com.myredis.storage.StorageEngine;
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
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.myredis.security.AclEngine aclEngine;
    private HttpServer server;

    public WebServer(String host, int port, StorageEngine storage, CommandRegistry registry, com.myredis.security.AclEngine aclEngine) {
        this.host = host;
        this.port = port;
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/v1/", new CloudApiHandler(storage, registry, aclEngine));
        server.createContext("/api/", new ApiHandler(storage, registry, aclEngine));
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
