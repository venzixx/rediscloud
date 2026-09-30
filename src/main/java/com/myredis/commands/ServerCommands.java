package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ServerCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("PING", ServerCommands::ping);
        registry.put("ECHO", ServerCommands::echo);
        registry.put("INFO", ServerCommands::info);
        registry.put("DBSIZE", ServerCommands::dbsize);
        registry.put("FLUSHDB", ServerCommands::flushdb);
        registry.put("FLUSHALL", ServerCommands::flushdb);
        registry.put("TIME", ServerCommands::time);
        registry.put("COMMAND", ServerCommands::command);
        registry.put("QUIT", (args, storage) -> RespMessage.OK);
    }

    private static RespMessage ping(List<String> args, StorageEngine storage) {
        if (args.size() == 1) {
            return RespMessage.PONG;
        } else if (args.size() == 2) {
            return RespMessage.bulkString(args.get(1));
        }
        return RespMessage.error("ERR wrong number of arguments for 'ping' command");
    }

    private static RespMessage echo(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'echo' command");
        }
        return RespMessage.bulkString(args.get(1));
    }

    private static RespMessage info(List<String> args, StorageEngine storage) {
        String infoStr = storage.getMetrics().toRedisInfo(storage.dbSize());
        return RespMessage.bulkString(infoStr);
    }

    private static RespMessage dbsize(List<String> args, StorageEngine storage) {
        return RespMessage.integer(storage.dbSize());
    }

    private static RespMessage flushdb(List<String> args, StorageEngine storage) {
        storage.flushDb();
        return RespMessage.OK;
    }

    private static RespMessage time(List<String> args, StorageEngine storage) {
        long nowMillis = System.currentTimeMillis();
        long secs = nowMillis / 1000;
        long micros = (nowMillis % 1000) * 1000;

        List<RespMessage> res = new ArrayList<>(2);
        res.add(RespMessage.bulkString(String.valueOf(secs)));
        res.add(RespMessage.bulkString(String.valueOf(micros)));
        return RespMessage.array(res);
    }

    /**
     * Handshake response for clients issuing COMMAND or COMMAND DOCS
     */
    private static RespMessage command(List<String> args, StorageEngine storage) {
        // Return empty array for generic COMMAND requests
        return RespMessage.emptyArray();
    }
}
