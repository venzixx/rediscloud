package com.myredis.storage;

import com.myredis.stats.ServerMetrics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Thread-safe in-memory storage engine supporting Strings, Hashes, Lists, and Sets,
 * along with TTL expiration and metrics integration.
 */
public class StorageEngine {

    private final ConcurrentHashMap<String, RedisEntry> map = new ConcurrentHashMap<>();
    private final ServerMetrics metrics;

    public StorageEngine(ServerMetrics metrics) {
        this.metrics = metrics != null ? metrics : new ServerMetrics();
    }

    public ServerMetrics getMetrics() {
        return metrics;
    }

    // ==========================================
    // Internal Expiry Helpers
    // ==========================================

    private RedisEntry getValidEntry(String key) {
        RedisEntry entry = map.get(key);
        if (entry == null) {
            metrics.recordMiss();
            return null;
        }

        if (entry.isExpired()) {
            map.remove(key, entry);
            metrics.recordExpiredKey();
            metrics.recordMiss();
            return null;
        }

        metrics.recordHit();
        return entry;
    }

    public void cleanExpiredKeys() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RedisEntry> entry : map.entrySet()) {
            if (entry.getValue().isExpired(now)) {
                if (map.remove(entry.getKey(), entry.getValue())) {
                    metrics.recordExpiredKey();
                }
            }
        }
    }

    // ==========================================
    // Generic Key Operations
    // ==========================================

    public boolean exists(String key) {
        return getValidEntry(key) != null;
    }

    public int exists(List<String> keys) {
        int count = 0;
        for (String k : keys) {
            if (exists(k)) {
                count++;
            }
        }
        return count;
    }

    public int del(List<String> keys) {
        int count = 0;
        for (String k : keys) {
            if (map.remove(k) != null) {
                count++;
            }
        }
        return count;
    }

    public DataType type(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return DataType.NONE;
        return entry.getType();
    }

    public boolean expire(String key, long seconds) {
        return pexpire(key, seconds * 1000L);
    }

    public boolean pexpire(String key, long millis) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return false;
        long expireAt = System.currentTimeMillis() + millis;
        entry.setExpiresAtMillis(expireAt);
        return true;
    }

    public long ttl(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return -2;
        return entry.getTtlSeconds(System.currentTimeMillis());
    }

    public long pttl(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return -2;
        return entry.getTtlMillis(System.currentTimeMillis());
    }

    public boolean persist(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null || entry.getExpiresAtMillis() == -1) return false;
        entry.setExpiresAtMillis(-1);
        return true;
    }

    public boolean rename(String oldKey, String newKey) {
        RedisEntry entry = getValidEntry(oldKey);
        if (entry == null) return false;
        map.remove(oldKey);
        map.put(newKey, entry);
        return true;
    }

    public List<String> keys(String globPattern) {
        Pattern regex = globToRegex(globPattern);
        List<String> matching = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, RedisEntry> e : map.entrySet()) {
            if (e.getValue().isExpired(now)) {
                map.remove(e.getKey(), e.getValue());
                metrics.recordExpiredKey();
                continue;
            }
            if (regex.matcher(e.getKey()).matches()) {
                matching.add(e.getKey());
            }
        }
        return matching;
    }

    public int dbSize() {
        cleanExpiredKeys();
        return map.size();
    }

    public void flushDb() {
        map.clear();
    }

    // ==========================================
    // Strings
    // ==========================================

    public boolean set(String key, String value, Long expireAtMillis, boolean nx, boolean xx) {
        long now = System.currentTimeMillis();
        RedisEntry existing = map.get(key);
        if (existing != null && existing.isExpired(now)) {
            map.remove(key, existing);
            existing = null;
        }

        if (nx && existing != null) {
            return false;
        }
        if (xx && existing == null) {
            return false;
        }

        RedisEntry entry = new RedisEntry(DataType.STRING, value, expireAtMillis != null ? expireAtMillis : -1);
        map.put(key, entry);
        return true;
    }

    public String get(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return null;
        if (entry.getType() != DataType.STRING) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asString();
    }

    public List<String> mget(List<String> keys) {
        List<String> result = new ArrayList<>(keys.size());
        for (String k : keys) {
            try {
                result.add(get(k));
            } catch (WrongTypeException e) {
                result.add(null);
            }
        }
        return result;
    }

    public void mset(Map<String, String> kvs) {
        for (Map.Entry<String, String> entry : kvs.entrySet()) {
            set(entry.getKey(), entry.getValue(), null, false, false);
        }
    }

    public long incrBy(String key, long delta) {
        long now = System.currentTimeMillis();
        return (Long) map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            if (existing == null) {
                return new RedisEntry(DataType.STRING, String.valueOf(delta));
            }
            if (existing.getType() != DataType.STRING) {
                throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            try {
                long current = Long.parseLong(existing.asString());
                long updated = current + delta;
                existing.setValue(String.valueOf(updated));
                return existing;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ERR value is not an integer or out of range");
            }
        }).getValue().toString().transform(Long::parseLong);
    }

    public long append(String key, String value) {
        long now = System.currentTimeMillis();
        RedisEntry entry = map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            if (existing == null) {
                return new RedisEntry(DataType.STRING, value);
            }
            if (existing.getType() != DataType.STRING) {
                throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            existing.setValue(existing.asString() + value);
            return existing;
        });
        return entry.asString().length();
    }

    public long strlen(String key) {
        String val = get(key);
        return val != null ? val.length() : 0;
    }

    // ==========================================
    // Hashes
    // ==========================================

    @SuppressWarnings("unchecked")
    public int hset(String key, Map<String, String> fieldValues) {
        long now = System.currentTimeMillis();
        int[] added = new int[1];

        map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            Map<String, String> hash;
            if (existing == null) {
                hash = new ConcurrentHashMap<>();
                existing = new RedisEntry(DataType.HASH, hash);
            } else {
                if (existing.getType() != DataType.HASH) {
                    throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
                }
                hash = existing.asHash();
            }

            for (Map.Entry<String, String> fv : fieldValues.entrySet()) {
                if (hash.put(fv.getKey(), fv.getValue()) == null) {
                    added[0]++;
                }
            }
            return existing;
        });

        return added[0];
    }

    public String hget(String key, String field) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return null;
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asHash().get(field);
    }

    public Map<String, String> hgetall(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptyMap();
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return new LinkedHashMap<>(entry.asHash());
    }

    public int hdel(String key, List<String> fields) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return 0;
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        Map<String, String> hash = entry.asHash();
        int count = 0;
        for (String f : fields) {
            if (hash.remove(f) != null) {
                count++;
            }
        }
        if (hash.isEmpty()) {
            map.remove(key, entry);
        }
        return count;
    }

    public boolean hexists(String key, String field) {
        String val = hget(key, field);
        return val != null;
    }

    public int hlen(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return 0;
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asHash().size();
    }

    public Set<String> hkeys(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptySet();
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return new LinkedHashSet<>(entry.asHash().keySet());
    }

    public List<String> hvals(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptyList();
        if (entry.getType() != DataType.HASH) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return new ArrayList<>(entry.asHash().values());
    }

    // ==========================================
    // Lists
    // ==========================================

    public int lpush(String key, List<String> values) {
        long now = System.currentTimeMillis();
        RedisEntry entry = map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            List<String> list;
            if (existing == null) {
                list = Collections.synchronizedList(new LinkedList<>());
                existing = new RedisEntry(DataType.LIST, list);
            } else {
                if (existing.getType() != DataType.LIST) {
                    throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
                }
                list = existing.asList();
            }
            synchronized (list) {
                for (String v : values) {
                    list.add(0, v);
                }
            }
            return existing;
        });
        return entry.asList().size();
    }

    public int rpush(String key, List<String> values) {
        long now = System.currentTimeMillis();
        RedisEntry entry = map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            List<String> list;
            if (existing == null) {
                list = Collections.synchronizedList(new LinkedList<>());
                existing = new RedisEntry(DataType.LIST, list);
            } else {
                if (existing.getType() != DataType.LIST) {
                    throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
                }
                list = existing.asList();
            }
            synchronized (list) {
                list.addAll(values);
            }
            return existing;
        });
        return entry.asList().size();
    }

    public List<String> lpop(String key, int count) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptyList();
        if (entry.getType() != DataType.LIST) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        List<String> list = entry.asList();
        List<String> result = new ArrayList<>();
        synchronized (list) {
            int toPop = Math.min(count, list.size());
            for (int i = 0; i < toPop; i++) {
                result.add(list.remove(0));
            }
            if (list.isEmpty()) {
                map.remove(key, entry);
            }
        }
        return result;
    }

    public List<String> rpop(String key, int count) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptyList();
        if (entry.getType() != DataType.LIST) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        List<String> list = entry.asList();
        List<String> result = new ArrayList<>();
        synchronized (list) {
            int toPop = Math.min(count, list.size());
            for (int i = 0; i < toPop; i++) {
                result.add(list.remove(list.size() - 1));
            }
            if (list.isEmpty()) {
                map.remove(key, entry);
            }
        }
        return result;
    }

    public int llen(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return 0;
        if (entry.getType() != DataType.LIST) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asList().size();
    }

    public List<String> lrange(String key, int start, int stop) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptyList();
        if (entry.getType() != DataType.LIST) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        List<String> list = entry.asList();
        synchronized (list) {
            int size = list.size();
            if (size == 0) return Collections.emptyList();

            int normStart = start < 0 ? Math.max(0, size + start) : Math.min(start, size);
            int normStop = stop < 0 ? Math.max(-1, size + stop) : Math.min(stop, size - 1);

            if (normStart > normStop || normStart >= size) {
                return Collections.emptyList();
            }

            List<String> sub = new ArrayList<>();
            for (int i = normStart; i <= normStop; i++) {
                sub.add(list.get(i));
            }
            return sub;
        }
    }

    public String lindex(String key, int index) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return null;
        if (entry.getType() != DataType.LIST) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        List<String> list = entry.asList();
        synchronized (list) {
            int size = list.size();
            int actualIndex = index < 0 ? size + index : index;
            if (actualIndex < 0 || actualIndex >= size) {
                return null;
            }
            return list.get(actualIndex);
        }
    }

    // ==========================================
    // Sets
    // ==========================================

    public int sadd(String key, List<String> members) {
        long now = System.currentTimeMillis();
        int[] added = new int[1];

        map.compute(key, (k, existing) -> {
            if (existing != null && existing.isExpired(now)) {
                existing = null;
            }
            Set<String> set;
            if (existing == null) {
                set = Collections.newSetFromMap(new ConcurrentHashMap<>());
                existing = new RedisEntry(DataType.SET, set);
            } else {
                if (existing.getType() != DataType.SET) {
                    throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
                }
                set = existing.asSet();
            }

            for (String m : members) {
                if (set.add(m)) {
                    added[0]++;
                }
            }
            return existing;
        });

        return added[0];
    }

    public int srem(String key, List<String> members) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return 0;
        if (entry.getType() != DataType.SET) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        Set<String> set = entry.asSet();
        int count = 0;
        for (String m : members) {
            if (set.remove(m)) {
                count++;
            }
        }
        if (set.isEmpty()) {
            map.remove(key, entry);
        }
        return count;
    }

    public Set<String> smembers(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return Collections.emptySet();
        if (entry.getType() != DataType.SET) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return new LinkedHashSet<>(entry.asSet());
    }

    public boolean sismember(String key, String member) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return false;
        if (entry.getType() != DataType.SET) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asSet().contains(member);
    }

    public int scard(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return 0;
        if (entry.getType() != DataType.SET) {
            throw new WrongTypeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return entry.asSet().size();
    }

    // ==========================================
    // Web Dashboard / API Helpers
    // ==========================================

    public Map<String, Object> getKeyDetails(String key) {
        RedisEntry entry = getValidEntry(key);
        if (entry == null) return null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("key", key);
        details.put("type", entry.getType().getRedisName());
        long now = System.currentTimeMillis();
        details.put("ttl", entry.getTtlSeconds(now));
        details.put("createdAt", entry.getCreatedAtMillis());

        switch (entry.getType()) {
            case STRING -> {
                details.put("value", entry.asString());
                details.put("size", entry.asString().length());
            }
            case HASH -> {
                details.put("value", entry.asHash());
                details.put("size", entry.asHash().size());
            }
            case LIST -> {
                details.put("value", new ArrayList<>(entry.asList()));
                details.put("size", entry.asList().size());
            }
            case SET -> {
                details.put("value", new ArrayList<>(entry.asSet()));
                details.put("size", entry.asSet().size());
            }
            default -> details.put("value", null);
        }

        return details;
    }

    public List<Map<String, Object>> getAllKeysMetadata() {
        cleanExpiredKeys();
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, RedisEntry> e : map.entrySet()) {
            String key = e.getKey();
            RedisEntry entry = e.getValue();
            if (entry.isExpired(now)) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("key", key);
            meta.put("type", entry.getType().getRedisName());
            meta.put("ttl", entry.getTtlSeconds(now));
            meta.put("createdAt", entry.getCreatedAtMillis());

            int size = 0;
            String preview = "";
            switch (entry.getType()) {
                case STRING -> {
                    String str = entry.asString();
                    size = str.length();
                    preview = str.length() > 60 ? str.substring(0, 57) + "..." : str;
                }
                case HASH -> {
                    Map<String, String> h = entry.asHash();
                    size = h.size();
                    preview = h.entrySet().stream().limit(3)
                            .map(kv -> kv.getKey() + ": " + kv.getValue())
                            .reduce((a, b) -> a + ", " + b).orElse("{}");
                    if (h.size() > 3) preview += ", ...";
                }
                case LIST -> {
                    List<String> l = entry.asList();
                    size = l.size();
                    preview = "[" + l.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("") + (l.size() > 3 ? ", ..." : "") + "]";
                }
                case SET -> {
                    Set<String> s = entry.asSet();
                    size = s.size();
                    preview = "{" + s.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("") + (s.size() > 3 ? ", ..." : "") + "}";
                }
            }
            meta.put("size", size);
            meta.put("preview", preview);

            list.add(meta);
        }
        return list;
    }

    public List<Map<String, Object>> exportData() {
        cleanExpiredKeys();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String k : map.keySet()) {
            Map<String, Object> details = getKeyDetails(k);
            if (details != null) {
                list.add(details);
            }
        }
        return list;
    }

    private static Pattern globToRegex(String glob) {
        if (glob == null || glob.equals("*")) {
            return Pattern.compile(".*");
        }
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); ++i) {
            final char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                case '.' -> out.append("\\.");
                case '\\' -> out.append("\\\\");
                case '[' -> out.append('[');
                case ']' -> out.append(']');
                default -> out.append(c);
            }
        }
        out.append('$');
        return Pattern.compile(out.toString());
    }

    public static class WrongTypeException extends RuntimeException {
        public WrongTypeException(String message) {
            super(message);
        }
    }
}
