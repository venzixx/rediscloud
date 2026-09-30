package com.myredis.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serializer for RESP2 messages into byte streams.
 */
public class RespEncoder {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_BULK_STRING = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);

    public static void encode(RespMessage msg, OutputStream out) throws IOException {
        if (msg == null) {
            out.write(NULL_BULK_STRING);
            return;
        }

        switch (msg.getType()) {
            case SIMPLE_STRING -> {
                out.write('+');
                out.write(msg.getStringValue().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case ERROR -> {
                out.write('-');
                out.write(msg.getStringValue().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
            case INTEGER -> {
                out.write(':');
                out.write(Long.toString(msg.getIntegerValue()).getBytes(StandardCharsets.US_ASCII));
                out.write(CRLF);
            }
            case BULK_STRING -> {
                if (msg.isNull()) {
                    out.write(NULL_BULK_STRING);
                } else {
                    byte[] data = msg.getByteValue();
                    if (data == null) {
                        data = msg.getStringValue() != null ? msg.getStringValue().getBytes(StandardCharsets.UTF_8) : new byte[0];
                    }
                    out.write('$');
                    out.write(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
                    out.write(CRLF);
                    out.write(data);
                    out.write(CRLF);
                }
            }
            case ARRAY -> {
                if (msg.isNull()) {
                    out.write(NULL_ARRAY);
                } else {
                    out.write('*');
                    out.write(Integer.toString(msg.getArrayValue().size()).getBytes(StandardCharsets.US_ASCII));
                    out.write(CRLF);
                    for (RespMessage elem : msg.getArrayValue()) {
                        encode(elem, out);
                    }
                }
            }
        }
    }

    public static byte[] toBytes(RespMessage msg) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            encode(msg, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected serialization error", e);
        }
    }
}
