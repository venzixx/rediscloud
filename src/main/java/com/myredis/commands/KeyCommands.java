package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.DataType;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("DEL", KeyCommands::del);
        registry.put("EXISTS", KeyCommands::exists);
        registry.put("EXPIRE", KeyCommands::expire);
        registry.put("PEXPIRE", KeyCommands::pexpire);
        registry.put("TTL", KeyCommands::ttl);
        registry.put("PTTL", KeyCommands::pttl);
        registry.put("PERSIST", KeyCommands::persist);
        registry.put("TYPE", KeyCommands::type);
        registry.put("KEYS", KeyCommands::keys);
        registry.put("RENAME", KeyCommands::rename);
    }

    private static RespMessage del(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RespMessage.error("ERR wrong number of arguments for 'del' command");
        }
        int removed = storage.del(args.subList(1, args.size()));
        return RespMessage.integer(removed);
    }

    private static RespMessage exists(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RespMessage.error("ERR wrong number of arguments for 'exists' command");
        }
        int count = storage.exists(args.subList(1, args.size()));
        return RespMessage.integer(count);
    }

    private static RespMessage expire(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'expire' command");
        }
        try {
            long seconds = Long.parseLong(args.get(2));
            boolean success = storage.expire(args.get(1), seconds);
            return RespMessage.integer(success ? 1 : 0);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        }
    }

    private static RespMessage pexpire(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'pexpire' command");
        }
        try {
            long millis = Long.parseLong(args.get(2));
            boolean success = storage.pexpire(args.get(1), millis);
            return RespMessage.integer(success ? 1 : 0);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        }
    }

    private static RespMessage ttl(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'ttl' command");
        }
        long ttl = storage.ttl(args.get(1));
        return RespMessage.integer(ttl);
    }

    private static RespMessage pttl(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'pttl' command");
        }
        long pttl = storage.pttl(args.get(1));
        return RespMessage.integer(pttl);
    }

    private static RespMessage persist(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'persist' command");
        }
        boolean ok = storage.persist(args.get(1));
        return RespMessage.integer(ok ? 1 : 0);
    }

    private static RespMessage type(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'type' command");
        }
        DataType dt = storage.type(args.get(1));
        return RespMessage.simpleString(dt.getRedisName());
    }

    private static RespMessage keys(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'keys' command");
        }
        List<String> matching = storage.keys(args.get(1));
        List<RespMessage> res = new ArrayList<>(matching.size());
        for (String k : matching) {
            res.add(RespMessage.bulkString(k));
        }
        return RespMessage.array(res);
    }

    private static RespMessage rename(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'rename' command");
        }
        boolean ok = storage.rename(args.get(1), args.get(2));
        if (!ok) {
            return RespMessage.error("ERR no such key");
        }
        return RespMessage.OK;
    }
}
