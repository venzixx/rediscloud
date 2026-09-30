package com.myredis.commands;

import com.myredis.protocol.RespMessage;
import com.myredis.storage.PersistenceEngine;
import com.myredis.storage.StorageEngine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry and router for all Redis commands.
 */
public class CommandRegistry {

    private final Map<String, Command> commands = new HashMap<>();
    private final PersistenceEngine persistenceEngine;

    public CommandRegistry(PersistenceEngine persistenceEngine) {
        this.persistenceEngine = persistenceEngine;
        registerAll();
    }

    private void registerAll() {
        ServerCommands.register(commands);
        StringCommands.register(commands);
        KeyCommands.register(commands);
        HashCommands.register(commands);
        ListCommands.register(commands);
        SetCommands.register(commands);
    }

    public RespMessage execute(List<String> args, StorageEngine storage) {
        if (args == null || args.isEmpty()) {
            return RespMessage.error("ERR empty command");
        }

        String commandName = args.get(0).toUpperCase();
        Command cmd = commands.get(commandName);

        if (cmd == null) {
            return RespMessage.error("ERR unknown command '" + args.get(0) + "'");
        }

        try {
            RespMessage response = cmd.execute(args, storage);
            storage.getMetrics().incrementCommands();

            // If command executed successfully (did not return error), record in AOF
            if (response.getType() != com.myredis.protocol.RespType.ERROR && persistenceEngine != null) {
                persistenceEngine.recordCommand(args);
            }

            return response;
        } catch (StorageEngine.WrongTypeException e) {
            return RespMessage.error(e.getMessage());
        } catch (IllegalArgumentException e) {
            return RespMessage.error(e.getMessage());
        } catch (Exception e) {
            return RespMessage.error("ERR " + (e.getMessage() != null ? e.getMessage() : "internal error"));
        }
    }
}
