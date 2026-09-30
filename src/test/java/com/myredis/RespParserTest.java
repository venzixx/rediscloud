package com.myredis;

import com.myredis.protocol.RespEncoder;
import com.myredis.protocol.RespMessage;
import com.myredis.protocol.RespParser;
import com.myredis.protocol.RespType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RespParserTest {

    @Test
    public void testParseSimpleString() throws IOException {
        String input = "+OK\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.SIMPLE_STRING, msg.getType());
        assertEquals("OK", msg.getStringValue());
    }

    @Test
    public void testParseError() throws IOException {
        String input = "-ERR unknown command\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.ERROR, msg.getType());
        assertEquals("ERR unknown command", msg.getStringValue());
    }

    @Test
    public void testParseInteger() throws IOException {
        String input = ":1000\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.INTEGER, msg.getType());
        assertEquals(1000L, msg.getIntegerValue());
    }

    @Test
    public void testParseBulkString() throws IOException {
        String input = "$5\r\nhello\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.BULK_STRING, msg.getType());
        assertEquals("hello", msg.getStringValue());
    }

    @Test
    public void testParseNullBulkString() throws IOException {
        String input = "$-1\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.BULK_STRING, msg.getType());
        assertTrue(msg.isNull());
    }

    @Test
    public void testParseArray() throws IOException {
        String input = "*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.ARRAY, msg.getType());
        assertEquals(2, msg.getArrayValue().size());
        assertEquals(List.of("ECHO", "hello"), msg.toCommandArgs());
    }

    @Test
    public void testParseInlineCommand() throws IOException {
        String input = "PING \"Hello World\"\r\n";
        RespParser parser = new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        RespMessage msg = parser.parseNext();

        assertNotNull(msg);
        assertEquals(RespType.ARRAY, msg.getType());
        assertEquals(List.of("PING", "Hello World"), msg.toCommandArgs());
    }

    @Test
    public void testEncoderRoundtrip() throws IOException {
        RespMessage original = RespMessage.array(List.of(
                RespMessage.bulkString("SET"),
                RespMessage.bulkString("mykey"),
                RespMessage.bulkString("myval")
        ));
        byte[] bytes = RespEncoder.toBytes(original);

        RespParser parser = new RespParser(new ByteArrayInputStream(bytes));
        RespMessage parsed = parser.parseNext();

        assertNotNull(parsed);
        assertEquals(List.of("SET", "mykey", "myval"), parsed.toCommandArgs());
    }
}
