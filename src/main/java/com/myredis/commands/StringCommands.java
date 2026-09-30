package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StringCommands {

    public static void register(Map<String, Command> registry) {
        registry.put("SET", StringCommands::set);
        registry.put("GET", StringCommands::get);
        registry.put("MSET", StringCommands::mset);
        registry.put("MGET", StringCommands::mget);
        registry.put("INCR", StringCommands::incr);
        registry.put("DECR", StringCommands::decr);
        registry.put("INCRBY", StringCommands::incrby);
        registry.put("DECRBY", StringCommands::decrby);
        registry.put("APPEND", StringCommands::append);
        registry.put("STRLEN", StringCommands::strlen);
    }

    private static RespMessage set(List<String> args, StorageEngine storage) {
        if (args.size() < 3) {
            return RespMessage.error("ERR wrong number of arguments for 'set' command");
        }

        String key = args.get(1);
        String value = args.get(2);

        Long expireAtMillis = null;
        boolean nx = false;
        boolean xx = false;

        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).toUpperCase();
            switch (opt) {
                case "EX" -> {
                    if (i + 1 >= args.size()) return RespMessage.error("ERR syntax error");
                    try {
                        long seconds = Long.parseLong(args.get(++i));
                        if (seconds <= 0) return RespMessage.error("ERR invalid expire time in 'set' command");
                        expireAtMillis = System.currentTimeMillis() + (seconds * 1000L);
                    } catch (NumberFormatException e) {
                        return RespMessage.error("ERR value is not an integer or out of range");
                    }
                }
                case "PX" -> {
                    if (i + 1 >= args.size()) return RespMessage.error("ERR syntax error");
                    try {
                        long millis = Long.parseLong(args.get(++i));
                        if (millis <= 0) return RespMessage.error("ERR invalid expire time in 'set' command");
                        expireAtMillis = System.currentTimeMillis() + millis;
                    } catch (NumberFormatException e) {
                        return RespMessage.error("ERR value is not an integer or out of range");
                    }
                }
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                default -> {
                    return RespMessage.error("ERR syntax error");
                }
            }
        }

        if (nx && xx) {
            return RespMessage.error("ERR syntax error");
        }

        boolean success = storage.set(key, value, expireAtMillis, nx, xx);
        if (!success) {
            return RespMessage.nullBulkString();
        }
        return RespMessage.OK;
    }

    private static RespMessage get(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'get' command");
        }
        String val = storage.get(args.get(1));
        if (val == null) {
            return RespMessage.nullBulkString();
        }
        return RespMessage.bulkString(val);
    }

    private static RespMessage mset(List<String> args, StorageEngine storage) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RespMessage.error("ERR wrong number of arguments for 'mset' command");
        }

        Map<String, String> kvs = new HashMap<>();
        for (int i = 1; i < args.size(); i += 2) {
            kvs.put(args.get(i), args.get(i + 1));
        }
        storage.mset(kvs);
        return RespMessage.OK;
    }

    private static RespMessage mget(List<String> args, StorageEngine storage) {
        if (args.size() < 2) {
            return RespMessage.error("ERR wrong number of arguments for 'mget' command");
        }

        List<String> keys = args.subList(1, args.size());
        List<String> values = storage.mget(keys);
        List<RespMessage> result = new ArrayList<>(values.size());
        for (String v : values) {
            result.add(v != null ? RespMessage.bulkString(v) : RespMessage.nullBulkString());
        }
        return RespMessage.array(result);
    }

    private static RespMessage incr(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'incr' command");
        }
        try {
            long res = storage.incrBy(args.get(1), 1);
            return RespMessage.integer(res);
        } catch (IllegalArgumentException e) {
            return RespMessage.error(e.getMessage());
        }
    }

    private static RespMessage decr(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'decr' command");
        }
        try {
            long res = storage.incrBy(args.get(1), -1);
            return RespMessage.integer(res);
        } catch (IllegalArgumentException e) {
            return RespMessage.error(e.getMessage());
        }
    }

    private static RespMessage incrby(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'incrby' command");
        }
        try {
            long delta = Long.parseLong(args.get(2));
            long res = storage.incrBy(args.get(1), delta);
            return RespMessage.integer(res);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        } catch (IllegalArgumentException e) {
            return RespMessage.error(e.getMessage());
        }
    }

    private static RespMessage decrby(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'decrby' command");
        }
        try {
            long delta = Long.parseLong(args.get(2));
            long res = storage.incrBy(args.get(1), -delta);
            return RespMessage.integer(res);
        } catch (NumberFormatException e) {
            return RespMessage.error("ERR value is not an integer or out of range");
        } catch (IllegalArgumentException e) {
            return RespMessage.error(e.getMessage());
        }
    }

    private static RespMessage append(List<String> args, StorageEngine storage) {
        if (args.size() != 3) {
            return RespMessage.error("ERR wrong number of arguments for 'append' command");
        }
        long len = storage.append(args.get(1), args.get(2));
        return RespMessage.integer(len);
    }

    private static RespMessage strlen(List<String> args, StorageEngine storage) {
        if (args.size() != 2) {
            return RespMessage.error("ERR wrong number of arguments for 'strlen' command");
        }
        long len = storage.strlen(args.get(1));
        return RespMessage.integer(len);
    }
}
