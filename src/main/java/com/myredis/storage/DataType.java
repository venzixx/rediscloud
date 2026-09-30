package com.myredis.storage;

public enum DataType {
    STRING("string"),
    LIST("list"),
    HASH("hash"),
    SET("set"),
    NONE("none");

    private final String redisName;

    DataType(String redisName) {
        this.redisName = redisName;
    }

    public String getRedisName() {
        return redisName;
    }
}
