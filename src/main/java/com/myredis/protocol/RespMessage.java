package com.myredis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a parsed or outbound RESP2 message.
 */
public final class RespMessage {

    private final RespType type;
    private final String stringValue;
    private final byte[] byteValue;
    private final Long integerValue;
    private final List<RespMessage> arrayValue;
    private final boolean isNull;

    private RespMessage(RespType type, String stringValue, byte[] byteValue, Long integerValue, List<RespMessage> arrayValue, boolean isNull) {
        this.type = type;
        this.stringValue = stringValue;
        this.byteValue = byteValue;
        this.integerValue = integerValue;
        this.arrayValue = arrayValue != null ? Collections.unmodifiableList(arrayValue) : null;
        this.isNull = isNull;
    }

    public static RespMessage simpleString(String value) {
        return new RespMessage(RespType.SIMPLE_STRING, Objects.requireNonNull(value), null, null, null, false);
    }

    public static final RespMessage OK = simpleString("OK");
    public static final RespMessage PONG = simpleString("PONG");

    public static RespMessage error(String message) {
        return new RespMessage(RespType.ERROR, Objects.requireNonNull(message), null, null, null, false);
    }

    public static RespMessage integer(long value) {
        return new RespMessage(RespType.INTEGER, null, null, value, null, false);
    }

    public static RespMessage bulkString(String value) {
        if (value == null) {
            return nullBulkString();
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return new RespMessage(RespType.BULK_STRING, value, bytes, null, null, false);
    }

    public static RespMessage bulkString(byte[] bytes) {
        if (bytes == null) {
            return nullBulkString();
        }
        String str = new String(bytes, StandardCharsets.UTF_8);
        return new RespMessage(RespType.BULK_STRING, str, bytes, null, null, false);
    }

    public static RespMessage nullBulkString() {
        return new RespMessage(RespType.BULK_STRING, null, null, null, null, true);
    }

    public static RespMessage array(List<RespMessage> elements) {
        if (elements == null) {
            return nullArray();
        }
        return new RespMessage(RespType.ARRAY, null, null, null, new ArrayList<>(elements), false);
    }

    public static RespMessage nullArray() {
        return new RespMessage(RespType.ARRAY, null, null, null, null, true);
    }

    public static RespMessage emptyArray() {
        return new RespMessage(RespType.ARRAY, null, null, null, Collections.emptyList(), false);
    }

    public RespType getType() {
        return type;
    }

    public String getStringValue() {
        return stringValue;
    }

    public byte[] getByteValue() {
        return byteValue;
    }

    public Long getIntegerValue() {
        return integerValue;
    }

    public List<RespMessage> getArrayValue() {
        return arrayValue;
    }

    public boolean isNull() {
        return isNull;
    }

    /**
     * Extracts strings from an Array message, typically for command arguments.
     */
    public List<String> toCommandArgs() {
        if (type != RespType.ARRAY || arrayValue == null) {
            return Collections.emptyList();
        }
        List<String> args = new ArrayList<>(arrayValue.size());
        for (RespMessage msg : arrayValue) {
            if (msg.getStringValue() != null) {
                args.add(msg.getStringValue());
            } else if (msg.getIntegerValue() != null) {
                args.add(msg.getIntegerValue().toString());
            } else {
                args.add("");
            }
        }
        return args;
    }

    @Override
    public String toString() {
        if (isNull) return "RespMessage(NULL " + type + ")";
        return switch (type) {
            case SIMPLE_STRING -> "+" + stringValue;
            case ERROR -> "-" + stringValue;
            case INTEGER -> ":" + integerValue;
            case BULK_STRING -> "$" + (stringValue != null ? stringValue : "(bytes)");
            case ARRAY -> "*[" + (arrayValue != null ? arrayValue.size() : 0) + " items]";
        };
    }
}
