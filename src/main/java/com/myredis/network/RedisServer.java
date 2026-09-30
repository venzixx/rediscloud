package com.myredis.network;

import com.myredis.commands.CommandRegistry;
import com.myredis.storage.StorageEngine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP Server for Redis clients.
 * Listens on port 6379 and delegates each incoming client connection
 * to a lightweight Java Virtual Thread.
 */
public class RedisServer {

    private final String host;
    private final int port;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.myredis.security.AclEngine aclEngine;
    private final com.myredis.storage.VirtualDatabaseManager virtualDbManager;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public RedisServer(String host, int port, StorageEngine storage, CommandRegistry registry, com.myredis.security.AclEngine aclEngine, com.myredis.storage.VirtualDatabaseManager virtualDbManager) {
        this.host = host;
        this.port = port;
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.virtualDbManager = virtualDbManager;
    }

    public RedisServer(String host, int port, StorageEngine storage, CommandRegistry registry, com.myredis.security.AclEngine aclEngine) {
        this(host, port, storage, registry, aclEngine, null);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, port));
        running = true;

        System.out.println("=================================================");
        System.out.println("  * Running Redis Server on " + host + ":" + port);
        System.out.println("  * Concurrency Engine: Java 25 Virtual Threads");
        System.out.println("  * Security Engine:    ACL & Key-Level Security (RLS)");
        System.out.println("  * Multi-Tenancy:      Database Virtualization Engine");
        System.out.println("  * Compatible with standard redis-cli, Jedis, Prisma");
        System.out.println("=================================================");

        // Accept loop running on a dedicated virtual thread
        Thread.ofVirtual().name("redis-accept-loop").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                storage.getMetrics().clientConnected();

                // Spawn a lightweight virtual thread per client
                Thread.ofVirtual()
                        .name("client-" + socket.getRemoteSocketAddress())
                        .start(new ClientConnection(socket, storage, registry, aclEngine, virtualDbManager));

            } catch (IOException e) {
                if (!running) {
                    break;
                }
                System.err.println("[RedisServer] Accept failed: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public int getPort() {
        return port;
    }
}
