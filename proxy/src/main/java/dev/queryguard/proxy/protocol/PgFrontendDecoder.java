package dev.queryguard.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frames client bytes into {@link FrontendMessage}s.
 *
 * <p>Two states. Before startup the client's messages have no type byte: just a length and a
 * code (SSLRequest, GSSENCRequest, CancelRequest, or the StartupMessage itself). After the
 * StartupMessage every message is {@code type(1) + length(4, counts itself) + body}.
 *
 * <p>ByteToMessageDecoder keeps unread bytes in a cumulation buffer, so a message split across
 * TCP reads simply waits here until the rest arrives, and several messages in one read come
 * out one at a time.
 */
public final class PgFrontendDecoder extends ByteToMessageDecoder {

    public static final int SSL_REQUEST = 80877103;
    public static final int GSSENC_REQUEST = 80877104;
    public static final int CANCEL_REQUEST = 80877102;
    public static final int PROTOCOL_3_0 = 196608;

    private static final int MAX_STARTUP_LENGTH = 10_000;
    private static final int MAX_MESSAGE_LENGTH = 1 << 30; // Postgres' own limit

    private boolean startupPhase = true;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (startupPhase) {
            decodeUntyped(in, out);
        } else {
            decodeTyped(in, out);
        }
    }

    private void decodeUntyped(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 8) {
            return;
        }
        int length = in.getInt(in.readerIndex());
        if (length < 8 || length > MAX_STARTUP_LENGTH) {
            throw new CorruptedFrameException("bad startup packet length " + length);
        }
        if (in.readableBytes() < length) {
            return;
        }
        int code = in.getInt(in.readerIndex() + 4);
        ByteBuf raw = in.readRetainedSlice(length);
        switch (code) {
            case SSL_REQUEST -> out.add(new FrontendMessage.SslRequest(raw));
            case GSSENC_REQUEST -> out.add(new FrontendMessage.GssEncRequest(raw));
            case CANCEL_REQUEST -> out.add(new FrontendMessage.CancelRequest(raw));
            default -> {
                // After SSL/GSS refusals the client sends a real startup. Anything else
                // (protocol 2, 3.2...) goes to Postgres as-is and Postgres answers it.
                startupPhase = false;
                Map<String, String> params = code == PROTOCOL_3_0 ? readParams(raw) : Map.of();
                out.add(new FrontendMessage.StartupMessage(code, params, raw));
            }
        }
    }

    private void decodeTyped(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }
        int length = in.getInt(in.readerIndex() + 1);
        if (length < 4 || length > MAX_MESSAGE_LENGTH) {
            throw new CorruptedFrameException("bad message length " + length);
        }
        if (in.readableBytes() < length + 1) {
            return;
        }
        char type = (char) in.getByte(in.readerIndex());
        ByteBuf raw = in.readRetainedSlice(length + 1);
        out.add(parse(type, raw));
    }

    static FrontendMessage parse(char type, ByteBuf raw) {
        ByteBuf body = raw.duplicate().skipBytes(5);
        return switch (type) {
            case 'Q' -> new FrontendMessage.Query(readCString(body), raw);
            case 'P' -> {
                String name = readCString(body);
                String sql = readCString(body);
                int n = body.readUnsignedShort();
                int[] types = new int[n];
                for (int i = 0; i < n; i++) {
                    types[i] = body.readInt();
                }
                yield new FrontendMessage.Parse(name, sql, types, raw);
            }
            case 'B' -> {
                String portal = readCString(body);
                String statement = readCString(body);
                yield new FrontendMessage.Bind(portal, statement, raw);
            }
            case 'E' -> {
                String portal = readCString(body);
                yield new FrontendMessage.Execute(portal, body.readInt(), raw);
            }
            case 'D' -> {
                char kind = (char) body.readByte();
                yield new FrontendMessage.Describe(kind, readCString(body), raw);
            }
            case 'C' -> {
                char kind = (char) body.readByte();
                yield new FrontendMessage.Close(kind, readCString(body), raw);
            }
            case 'S' -> new FrontendMessage.Sync(raw);
            case 'X' -> new FrontendMessage.Terminate(raw);
            default -> new FrontendMessage.Other(type, raw);
        };
    }

    private static Map<String, String> readParams(ByteBuf raw) {
        ByteBuf body = raw.duplicate().skipBytes(8);
        Map<String, String> params = new LinkedHashMap<>();
        while (body.isReadable()) {
            String key = readCString(body);
            if (key.isEmpty()) {
                break;
            }
            params.put(key, readCString(body));
        }
        return params;
    }

    /**
     * Strings on the wire are null-terminated. We assume client_encoding is UTF8, which is
     * what every modern driver uses; a LATIN1 client would get its SQL mis-decoded here.
     */
    public static String readCString(ByteBuf buf) {
        int len = buf.bytesBefore((byte) 0);
        if (len < 0) {
            throw new CorruptedFrameException("unterminated string in message");
        }
        String s = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
        buf.skipBytes(len + 1);
        return s;
    }
}
