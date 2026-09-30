package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HashCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("HSET", HashCommands::hset);
        registry.put("HGET", HashCommands::hget);
        registry.put("HDEL", HashCommands::hdel);
        registry.put("HGETALL", HashCommands::hgetall);
        registry.put("HEXISTS", HashCommands::hexists);
        registry.put("HLEN", HashCommands::hlen);
        registry.put("HKEYS", HashCommands::hkeys);
        registry.put("HVALS", HashCommands::hvals);
    }

    private static RespMessage hset(List<String> args, StorageEngine storage) {
        if (args.size() < 4 || (args.size() - 2) % 2 != 0) {
            return RespMessage.error("ERR wrong number of arguments for 'hset' command");
        }
        String key = args.get(1);
        Map<String, String> fieldValues = new HashMap<>();
        for (int i = 2; i < args.size(); i += 2) {
            fieldValues.put(args.get(i), args.get(i + 1));
        }
        int added = storage.hset(key, fieldValues);
        return RespMessage.integer(added);
    }

    private static RespMessage hget(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'hget' command");
        }
        String val = storage.hget(args.get(1), args.get(2));
        if (val == null) {
            return RespMessage.nullBulkString();
        }
        return RespMessage.bulkString(val);
    }

    private static RespMessage hdel(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'hdel' command");
        }
        int removed = storage.hdel(args.get(1), args.subList(2, args.size()));
        return RespMessage.integer(removed);
    }

    private static RespMessage hgetall(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'hgetall' command");
        }
        Map<String, String> map = storage.hgetall(args.get(1));
        List<RespMessage> result = new ArrayList<>(map.size() * 2);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            result.add(RespMessage.bulkString(entry.getKey()));
            result.add(RespMessage.bulkString(entry.getValue()));
        }
        return RespMessage.array(result);
    }

    private static RespMessage hexists(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'hexists' command");
        }
        boolean exists = storage.hexists(args.get(1), args.get(2));
        return RespMessage.integer(exists ? 1 : 0);
    }

    private static RespMessage hlen(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'hlen' command");
        }
        int len = storage.hlen(args.get(1));
        return RespMessage.integer(len);
    }

    private static RespMessage hkeys(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'hkeys' command");
        }
        Set<String> keys = storage.hkeys(args.get(1));
        List<RespMessage> result = new ArrayList<>(keys.size());
        for (String k : keys) {
            result.add(RespMessage.bulkString(k));
        }
        return RespMessage.array(result);
    }

    private static RespMessage hvals(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'hvals' command");
        }
        List<String> vals = storage.hvals(args.get(1));
        List<RespMessage> result = new ArrayList<>(vals.size());
        for (String v : vals) {
            result.add(RespMessage.bulkString(v));
        }
        return RespMessage.array(result);
    }
}
