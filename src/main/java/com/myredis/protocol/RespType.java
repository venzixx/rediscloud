package com.myredis.protocol;

/**
 * RESP2 data types supported by Redis.
 */
public enum RespType {
    SIMPLE_STRING('+'),
    ERROR('-'),
    INTEGER(':'),
    BULK_STRING('$'),
    ARRAY('*');

    private final char prefix;

    RespType(char prefix) {
        this.prefix = prefix;
    }

    public char getPrefix() {
        return prefix;
    }

    public static RespType fromPrefix(int b) {
        return switch (b) {
            case '+' -> SIMPLE_STRING;
            case '-' -> ERROR;
            case ':' -> INTEGER;
            case '$' -> BULK_STRING;
            case '*' -> ARRAY;
            default -> null;
        };
    }
}
