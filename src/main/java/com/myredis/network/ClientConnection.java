package com.myredis.network;

import com.myredis.commands.CommandRegistry;
import com.myredis.protocol.RespEncoder;
import com.myredis.protocol.RespMessage;
import com.myredis.protocol.RespParser;
import com.myredis.storage.StorageEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;

/**
 * Handles communication with a single Redis client over TCP.
 * Runs concurrently on a lightweight Java Virtual Thread.
 */
public class ClientConnection implements Runnable {

    private final Socket socket;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final com.myredis.security.AclEngine aclEngine;
    private com.myredis.security.User authenticatedUser;

    public ClientConnection(Socket socket, StorageEngine storage, CommandRegistry registry, com.myredis.security.AclEngine aclEngine) {
        this.socket = socket;
        this.storage = storage;
        this.registry = registry;
        this.aclEngine = aclEngine;
        this.authenticatedUser = aclEngine != null ? aclEngine.getDefaultAdminUser() : null;
    }

    @Override
    public void run() {
        try (socket;
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            // Enable TCP_NODELAY for minimum latency
            socket.setTcpNoDelay(true);

            RespParser parser = new RespParser(in);

            while (!socket.isClosed()) {
                RespMessage request;
                try {
                    request = parser.parseNext();
                } catch (EOFException e) {
                    break; // Client closed connection
                } catch (SocketException e) {
                    break; // Connection reset or broken
                } catch (IOException e) {
                    // Protocol error, respond with error and continue or close
                    RespEncoder.encode(RespMessage.error("ERR Protocol error: " + e.getMessage()), out);
                    out.flush();
                    break;
                }

                if (request == null) {
                    break; // End of stream
                }

                List<String> args = request.toCommandArgs();
                if (args.isEmpty()) {
                    continue;
                }

                String cmdName = args.get(0).toUpperCase();

                if ("QUIT".equals(cmdName)) {
                    RespEncoder.encode(RespMessage.OK, out);
                    out.flush();
                    break;
                }

                // Handle AUTH command
                if ("AUTH".equals(cmdName) && aclEngine != null) {
                    if (args.size() == 2) {
                        var u = aclEngine.authenticate("admin", args.get(1));
                        if (u != null) {
                            authenticatedUser = u;
                            RespEncoder.encode(RespMessage.OK, out);
                        } else {
                            RespEncoder.encode(RespMessage.error("WRONGPASS invalid username-password pair or user is disabled."), out);
                        }
                    } else if (args.size() == 3) {
                        var u = aclEngine.authenticate(args.get(1), args.get(2));
                        if (u != null) {
                            authenticatedUser = u;
                            RespEncoder.encode(RespMessage.OK, out);
                        } else {
                            RespEncoder.encode(RespMessage.error("WRONGPASS invalid username-password pair or user is disabled."), out);
                        }
                    } else {
                        RespEncoder.encode(RespMessage.error("ERR wrong number of arguments for 'auth' command"), out);
                    }
                    out.flush();
                    continue;
                }

                // Enforce ACL & RLS
                if (aclEngine != null) {
                    try {
                        aclEngine.verifyPermission(authenticatedUser, args);
                    } catch (SecurityException se) {
                        RespEncoder.encode(RespMessage.error(se.getMessage()), out);
                        out.flush();
                        continue;
                    }
                }

                RespMessage response = registry.execute(args, storage);
                RespEncoder.encode(response, out);
                out.flush();
            }

        } catch (IOException ignored) {
        } finally {
            storage.getMetrics().clientDisconnected();
        }
    }
}
