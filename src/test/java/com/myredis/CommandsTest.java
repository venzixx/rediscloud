package com.myredis;

import com.myredis.commands.CommandRegistry;
import com.myredis.protocol.RespMessage;
import com.myredis.protocol.RespType;
import com.myredis.stats.ServerMetrics;
import com.myredis.storage.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CommandsTest {

    private StorageEngine storage;
    private CommandRegistry registry;

    @BeforeEach
    public void setup() {
        storage = new StorageEngine(new ServerMetrics());
        registry = new CommandRegistry(null);
    }

    @Test
    public void testPingAndEcho() {
        RespMessage ping1 = registry.execute(List.of("PING"), storage);
        assertEquals(RespType.SIMPLE_STRING, ping1.getType());
        assertEquals("PONG", ping1.getStringValue());

        RespMessage ping2 = registry.execute(List.of("PING", "Hello"), storage);
        assertEquals("Hello", ping2.getStringValue());

        RespMessage echo = registry.execute(List.of("ECHO", "World"), storage);
        assertEquals("World", echo.getStringValue());
    }

    @Test
    public void testSetAndGet() {
        RespMessage setResp = registry.execute(List.of("SET", "msg", "Antigravity"), storage);
        assertEquals(RespType.SIMPLE_STRING, setResp.getType());
        assertEquals("OK", setResp.getStringValue());

        RespMessage getResp = registry.execute(List.of("GET", "msg"), storage);
        assertEquals(RespType.BULK_STRING, getResp.getType());
        assertEquals("Antigravity", getResp.getStringValue());
    }

    @Test
    public void testSetWithOptions() {
        // NX when key exists should return nil
        registry.execute(List.of("SET", "foo", "bar"), storage);
        RespMessage setNx = registry.execute(List.of("SET", "foo", "newbar", "NX"), storage);
        assertTrue(setNx.isNull());

        // XX when key does not exist should return nil
        RespMessage setXx = registry.execute(List.of("SET", "baz", "val", "XX"), storage);
        assertTrue(setXx.isNull());
    }

    @Test
    public void testDelAndExists() {
        registry.execute(List.of("SET", "k1", "v1"), storage);
        registry.execute(List.of("SET", "k2", "v2"), storage);

        RespMessage existsResp = registry.execute(List.of("EXISTS", "k1", "k2", "k3"), storage);
        assertEquals(2L, existsResp.getIntegerValue());

        RespMessage delResp = registry.execute(List.of("DEL", "k1"), storage);
        assertEquals(1L, delResp.getIntegerValue());

        RespMessage getAfterDel = registry.execute(List.of("GET", "k1"), storage);
        assertTrue(getAfterDel.isNull());
    }

    @Test
    public void testUnknownCommand() {
        RespMessage err = registry.execute(List.of("UNKNOWN_XYZ"), storage);
        assertEquals(RespType.ERROR, err.getType());
        assertTrue(err.getStringValue().contains("unknown command"));
    }
}
