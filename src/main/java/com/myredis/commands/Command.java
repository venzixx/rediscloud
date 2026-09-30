package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.StorageEngine;

import java.util.List;

@FunctionalInterface
public interface Command {
    /**
     * Executes a Redis command.
     * @param args The arguments including the command name as args.get(0)
     * @param storage The storage engine instance
     * @return The RESP message response
     */
    RespMessage execute(List<String> args, StorageEngine storage);
}
