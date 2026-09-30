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

    public ClientConnection(Socket socket, StorageEngine storage, CommandRegistry registry) {
        this.socket = socket;
        this.storage = storage;
        this.registry = registry;
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

                boolean isQuit = "QUIT".equalsIgnoreCase(args.get(0));

                RespMessage response = registry.execute(args, storage);
                RespEncoder.encode(response, out);
                out.flush();

                if (isQuit) {
                    break;
                }
            }

        } catch (IOException ignored) {
        } finally {
            storage.getMetrics().clientDisconnected();
        }
    }
}
