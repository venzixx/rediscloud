package com.myredis;

import com.myredis.stats.ServerMetrics;
import com.myredis.storage.DataType;
import com.myredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StorageEngineTest {

    private StorageEngine storage;

    @BeforeEach
    public void setup() {
        storage = new StorageEngine(new ServerMetrics());
    }

    @Test
    public void testStringOperations() {
        storage.set("k1", "v1", null, false, false);
        assertEquals("v1", storage.get("k1"));

        // INCR
        storage.set("counter", "10", null, false, false);
        assertEquals(11, storage.incrBy("counter", 1));
        assertEquals(15, storage.incrBy("counter", 4));
        assertEquals("15", storage.get("counter"));

        // APPEND & STRLEN
        storage.append("k1", "_extra");
        assertEquals("v1_extra", storage.get("k1"));
        assertEquals(8, storage.strlen("k1"));
    }

    @Test
    public void testHashOperations() {
        int added = storage.hset("user:1", Map.of("name", "Alice", "role", "admin"));
        assertEquals(2, added);

        assertEquals("Alice", storage.hget("user:1", "name"));
        assertEquals("admin", storage.hget("user:1", "role"));
        assertTrue(storage.hexists("user:1", "name"));
        assertFalse(storage.hexists("user:1", "nonexistent"));

        Map<String, String> all = storage.hgetall("user:1");
        assertEquals(2, all.size());
        assertEquals("Alice", all.get("name"));

        int deleted = storage.hdel("user:1", List.of("role"));
        assertEquals(1, deleted);
        assertNull(storage.hget("user:1", "role"));
    }

    @Test
    public void testListOperations() {
        storage.rpush("mylist", List.of("a", "b", "c"));
        assertEquals(3, storage.llen("mylist"));

        assertEquals(List.of("a", "b", "c"), storage.lrange("mylist", 0, -1));
        assertEquals(List.of("a", "b"), storage.lrange("mylist", 0, 1));

        storage.lpush("mylist", List.of("head"));
        assertEquals("head", storage.lindex("mylist", 0));

        List<String> popped = storage.lpop("mylist", 1);
        assertEquals(List.of("head"), popped);
        assertEquals(3, storage.llen("mylist"));
    }

    @Test
    public void testSetOperations() {
        int added = storage.sadd("myset", List.of("apple", "banana", "apple"));
        assertEquals(2, added);
        assertEquals(2, storage.scard("myset"));

        assertTrue(storage.sismember("myset", "apple"));
        assertFalse(storage.sismember("myset", "grape"));

        int removed = storage.srem("myset", List.of("banana"));
        assertEquals(1, removed);
        assertEquals(1, storage.scard("myset"));
    }

    @Test
    public void testExpiration() throws InterruptedException {
        storage.set("temp", "value", null, false, false);
        assertTrue(storage.exists("temp"));
        assertEquals(-1, storage.ttl("temp"));

        storage.pexpire("temp", 100);
        assertTrue(storage.exists("temp"));

        Thread.sleep(150);
        assertFalse(storage.exists("temp"));
        assertNull(storage.get("temp"));
        assertEquals(-2, storage.ttl("temp"));
    }

    @Test
    public void testKeysPatternMatching() {
        storage.set("user:1", "a", null, false, false);
        storage.set("user:2", "b", null, false, false);
        storage.set("order:1", "c", null, false, false);

        List<String> userKeys = storage.keys("user:*");
        assertEquals(2, userKeys.size());
        assertTrue(userKeys.contains("user:1"));
        assertTrue(userKeys.contains("user:2"));

        List<String> allKeys = storage.keys("*");
        assertEquals(3, allKeys.size());
    }
}
