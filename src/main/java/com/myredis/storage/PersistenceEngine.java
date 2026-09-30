package com.myredis.storage;

import com.myredis.protocol.RespEncoder;
import com.myredis.protocol.RespMessage;
import com.myredis.protocol.RespParser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Append-Only File (AOF) persistence engine.
 * Logs mutating commands in standard RESP2 format to disk and replays them on startup.
 */
public class PersistenceEngine {

    private final Path aofPath;
    private final boolean enabled;
    private OutputStream aofOutputStream;

    private static final Set<String> WRITE_COMMANDS = Set.of(
            "SET", "DEL", "MSET", "INCR", "DECR", "INCRBY", "DECRBY", "APPEND",
            "EXPIRE", "PEXPIRE", "PERSIST", "RENAME", "FLUSHDB",
            "HSET", "HDEL", "LPUSH", "RPUSH", "LPOP", "RPOP", "SADD", "SREM"
    );

    private volatile boolean replaying = false;

    public PersistenceEngine(String filePath, boolean enabled) {
        this.aofPath = Paths.get(filePath);
        this.enabled = enabled;
    }

    public synchronized void init() throws IOException {
        if (!enabled) return;

        if (aofPath.getParent() != null) {
            Files.createDirectories(aofPath.getParent());
        }

        this.aofOutputStream = new BufferedOutputStream(
                Files.newOutputStream(aofPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        );
    }

    public synchronized void recordCommand(List<String> args) {
        if (!enabled || aofOutputStream == null || args == null || args.isEmpty() || replaying) {
            return;
        }

        String cmd = args.get(0).toUpperCase();
        if (!WRITE_COMMANDS.contains(cmd)) {
            return;
        }

        try {
            List<RespMessage> elements = new ArrayList<>(args.size());
            for (String arg : args) {
                elements.add(RespMessage.bulkString(arg));
            }
            RespMessage arrayMsg = RespMessage.array(elements);
            byte[] bytes = RespEncoder.toBytes(arrayMsg);
            aofOutputStream.write(bytes);
            aofOutputStream.flush();
        } catch (IOException e) {
            System.err.println("[AOF] Error writing to persistence log: " + e.getMessage());
        }
    }

    /**
     * Replays the AOF log file on server startup.
     */
    public void replay(Consumer<List<String>> commandExecutor) {
        if (!enabled || !Files.exists(aofPath)) {
            return;
        }

        replaying = true;
        int replayed = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(aofPath))) {
            RespParser parser = new RespParser(in);
            RespMessage msg;
            while (true) {
                try {
                    msg = parser.parseNext();
                    if (msg == null) break;
                } catch (EOFException e) {
                    break;
                }
                List<String> args = msg.toCommandArgs();
                if (!args.isEmpty()) {
                    commandExecutor.accept(args);
                    replayed++;
                }
            }
        } catch (Exception e) {
            System.err.println("[AOF] Notice during AOF replay: " + e.getMessage());
        } finally {
            replaying = false;
        }
        System.out.println("[AOF] Successfully replayed " + replayed + " commands from " + aofPath);
    }

    public synchronized void close() {
        if (aofOutputStream != null) {
            try {
                aofOutputStream.flush();
                aofOutputStream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
