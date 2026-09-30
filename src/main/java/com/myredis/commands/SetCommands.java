package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SetCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("SADD", SetCommands::sadd);
        registry.put("SREM", SetCommands::srem);
        registry.put("SMEMBERS", SetCommands::smembers);
        registry.put("SISMEMBER", SetCommands::sismember);
        registry.put("SCARD", SetCommands::scard);
    }

    private static RespMessage sadd(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'sadd' command");
        }
        int added = storage.sadd(args.get(1), args.subList(2, args.size()));
        return RespMessage.integer(added);
    }

    private static RespMessage srem(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'srem' command");
        }
        int removed = storage.srem(args.get(1), args.subList(2, args.size()));
        return RespMessage.integer(removed);
    }

    private static RespMessage smembers(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'smembers' command");
        }
        Set<String> set = storage.smembers(args.get(1));
        List<RespMessage> result = new ArrayList<>(set.size());
        for (String s : set) {
            result.add(RespMessage.bulkString(s));
        }
        return RespMessage.array(result);
    }

    private static RespMessage sismember(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'sismember' command");
        }
        boolean isMember = storage.sismember(args.get(1), args.get(2));
        return RespMessage.integer(isMember ? 1 : 0);
    }

    private static RespMessage scard(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'scard' command");
        }
        int card = storage.scard(args.get(1));
        return RespMessage.integer(card);
    }
}
