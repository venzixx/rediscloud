package com.myredis.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance streaming parser for RESP2 protocol.
 * Also handles inline commands (e.g. from netcat or telnet).
 */
public class RespParser {

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    /**
     * Reads the next RESP message from the stream.
     * Returns null if stream reached EOF cleanly before reading any byte.
     */
    public RespMessage parseNext() throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return null; // Clean EOF
        }

        return switch (firstByte) {
            case '+' -> parseSimpleString();
            case '-' -> parseError();
            case ':' -> parseInteger();
            case '$' -> parseBulkString();
            case '*' -> parseArray();
            default -> parseInlineCommand(firstByte);
        };
    }

    private RespMessage parseSimpleString() throws IOException {
        String line = readLine();
        return RespMessage.simpleString(line);
    }

    private RespMessage parseError() throws IOException {
        String line = readLine();
        return RespMessage.error(line);
    }

    private RespMessage parseInteger() throws IOException {
        String line = readLine();
        try {
            return RespMessage.integer(Long.parseLong(line));
        } catch (NumberFormatException e) {
            throw new IOException("Protocol error: invalid integer '" + line + "'");
        }
    }

    private RespMessage parseBulkString() throws IOException {
        String lengthLine = readLine();
        int length;
        try {
            length = Integer.parseInt(lengthLine);
        } catch (NumberFormatException e) {
            throw new IOException("Protocol error: invalid bulk string length '" + lengthLine + "'");
        }

        if (length == -1) {
            return RespMessage.nullBulkString();
        }

        if (length < 0) {
            throw new IOException("Protocol error: negative bulk string length " + length);
        }

        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException("Premature end of stream while reading bulk string data");
        }

        // Consume trailing \r\n
        int cr = in.read();
        int lf = in.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Protocol error: expected CRLF after bulk string data");
        }

        return RespMessage.bulkString(bytes);
    }

    private RespMessage parseArray() throws IOException {
        String countLine = readLine();
        int count;
        try {
            count = Integer.parseInt(countLine);
        } catch (NumberFormatException e) {
            throw new IOException("Protocol error: invalid array count '" + countLine + "'");
        }

        if (count == -1) {
            return RespMessage.nullArray();
        }

        if (count < 0) {
            throw new IOException("Protocol error: negative array length " + count);
        }

        List<RespMessage> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RespMessage element = parseNext();
            if (element == null) {
                throw new EOFException("Premature end of stream while reading array elements");
            }
            elements.add(element);
        }

        return RespMessage.array(elements);
    }

    /**
     * Parses inline commands, e.g. "PING\r\n" or "SET mykey hello\r\n" from telnet/netcat.
     */
    private RespMessage parseInlineCommand(int firstByte) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append((char) firstByte);

        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                }
                sb.append('\r');
                if (next != -1) {
                    sb.append((char) next);
                }
            } else if (b == '\n') {
                break;
            } else {
                sb.append((char) b);
            }
        }

        String raw = sb.toString().replace("\uFEFF", "").trim();
        if (raw.isEmpty()) {
            // Empty line, parse next
            return parseNext();
        }

        // Split into arguments handling basic quotes
        List<String> tokens = tokenize(raw);
        List<RespMessage> bulkStrings = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            bulkStrings.add(RespMessage.bulkString(token));
        }

        return RespMessage.array(bulkStrings);
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return sb.toString();
                }
                sb.append('\r');
                if (next != -1) {
                    sb.append((char) next);
                }
            } else if (b == '\n') {
                return sb.toString();
            } else {
                sb.append((char) b);
            }
        }
        if (sb.length() == 0) {
            throw new EOFException("Unexpected EOF while reading line");
        }
        return sb.toString();
    }

    private static List<String> tokenize(String input) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!inQuote && (c == '"' || c == '\'')) {
                inQuote = true;
                quoteChar = c;
            } else if (inQuote && c == quoteChar) {
                inQuote = false;
                quoteChar = 0;
            } else if (!inQuote && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    list.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            list.add(cur.toString());
        }
        return list;
    }
}
