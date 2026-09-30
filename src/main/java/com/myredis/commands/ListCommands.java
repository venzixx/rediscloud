package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ListCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("LPUSH", ListCommands::lpush);
        registry.put("RPUSH", ListCommands::rpush);
        registry.put("LPOP", ListCommands::lpop);
        registry.put("RPOP", ListCommands::rpop);
        registry.put("LLEN", ListCommands::llen);
        registry.put("LRANGE", ListCommands::lrange);
        registry.put("LINDEX", ListCommands::lindex);
    }

    private static RespMessage lpush(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'lpush' command");
        }
        int len = storage.lpush(args.get(1), args.subList(2, args.size()));
        return RespMessage.integer(len);
    }

    private static RespMessage rpush(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'rpush' command");
        }
        int len = storage.rpush(args.get(1), args.subList(2, args.size()));
        return RespMessage.integer(len);
    }

    private static RespMessage lpop(List<String> args, StorageEngine storage) {
        if (args.size() < 2 || args.size() > 3) {
            return RespMessage.error("ERR wrong number of arguments for 'lpop' command");
        }
        int count = 1;
        boolean hasCountArg = (args.size() == 3);
        if (hasCountArg) {
            try {
                count = Integer.parseInt(args.get(2));
                if (count < 0) return RespMessage.error("ERR value is out of range, must be positive");
            } catch (NumberFormatException e) {
                return RespMessage.error("ERR value is not an integer or out of range");
            }
        }

        List<String> popped = storage.lpop(args.get(1), count);
        if (popped.isEmpty()) {
            return RespMessage.nullBulkString();
        }

        if (!hasCountArg) {
            return RespMessage.bulkString(popped.get(0));
        }

        List<RespMessage> res = new ArrayList<>(popped.size());
        for (String p : popped) {
            res.add(RespMessage.bulkString(p));
        }
        return RespMessage.array(res);
    }

    private static RespMessage rpop(List<String> args, StorageEngine storage) {
        if (args.size() < 2 || args.size() > 3) {
            return RespMessage.error("ERR wrong number of arguments for 'rpop' command");
        }
        int count = 1;
        boolean hasCountArg = (args.size() == 3);
        if (hasCountArg) {
            try {
                count = Integer.parseInt(args.get(2));
                if (count < 0) return RespMessage.error("ERR value is out of range, must be positive");
            } catch (NumberFormatException e) {
                return RespMessage.error("ERR value is not an integer or out of range");
            }
        }

        List<String> popped = storage.rpop(args.get(1), count);
        if (popped.isEmpty()) {
            return RespMessage.nullBulkString();
        }

        if (!hasCountArg) {
            return RespMessage.bulkString(popped.get(0));
        }

        List<RespMessage> res = new ArrayList<>(popped.size());
        for (String p : popped) {
            res.add(RespMessage.bulkString(p));
        }
        return RespMessage.array(res);
    }

    private static RespMessage llen(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'llen' command");
        }
        int len = storage.llen(args.get(1));
        return RespMessage.integer(len);
    }

    private static RespMessage lrange(List<String> args, StorageEngine storage) {
        if (args.size() != 4) {
            return RespMessage.error("ERR wrong number of arguments for 'lrange' command");
        }
        try {
            int start = Integer.parseInt(args.get(2));
            int stop = Integer.parseInt(args.get(3));
            List<String> sub = storage.lrange(args.get(1), start, stop);
            List<RespMessage> result = new ArrayList<>(sub.size());
            for (String s : sub) {
                result.add(RespMessage.bulkString(s));
            }
            return RespMessage.array(result);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        }
    }

    private static RespMessage lindex(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'lindex' command");
        }
        try {
            int idx = Integer.parseInt(args.get(2));
            String val = storage.lindex(args.get(1), idx);
            if (val == null) {
                return RespMessage.nullBulkString();
            }
            return RespMessage.bulkString(val);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        }
    }
}
