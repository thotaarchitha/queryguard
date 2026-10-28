package dev.queryguard.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.charset.StandardCharsets;

/** Builders for the few messages QueryGuard writes itself. */
public final class PgMessages {

    private PgMessages() {
    }

    /**
     * ErrorResponse. Fields: S and V severity (V is the non-localized one newer clients read),
     * C SQLSTATE, M message, D detail, H hint. psql prints D and H on their own lines.
     */
    public static ByteBuf errorResponse(ByteBufAllocator alloc, String severity, String sqlstate,
                                        String message, String detail, String hint) {
        ByteBuf buf = alloc.buffer(64 + message.length());
        buf.writeByte('E');
        int lengthAt = buf.writerIndex();
        buf.writeInt(0);
        field(buf, 'S', severity);
        field(buf, 'V', severity);
        field(buf, 'C', sqlstate);
        field(buf, 'M', message);
        if (detail != null) {
            field(buf, 'D', detail);
        }
        if (hint != null) {
            field(buf, 'H', hint);
        }
        buf.writeByte(0);
        buf.setInt(lengthAt, buf.writerIndex() - lengthAt);
        return buf;
    }

    public static ByteBuf readyForQuery(ByteBufAllocator alloc, char txStatus) {
        ByteBuf buf = alloc.buffer(6);
        buf.writeByte('Z').writeInt(5).writeByte(txStatus);
        return buf;
    }

    public static ByteBuf query(ByteBufAllocator alloc, String sql) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = alloc.buffer(6 + bytes.length);
        buf.writeByte('Q').writeInt(4 + bytes.length + 1).writeBytes(bytes).writeByte(0);
        return buf;
    }

    public static ByteBuf parse(ByteBufAllocator alloc, String name, String sql, int[] paramTypes) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] q = sql.getBytes(StandardCharsets.UTF_8);
        int length = 4 + n.length + 1 + q.length + 1 + 2 + 4 * paramTypes.length;
        ByteBuf buf = alloc.buffer(length + 1);
        buf.writeByte('P').writeInt(length).writeBytes(n).writeByte(0).writeBytes(q).writeByte(0);
        buf.writeShort(paramTypes.length);
        for (int t : paramTypes) {
            buf.writeInt(t);
        }
        return buf;
    }

    /** The single-byte "no, I don't do TLS" answer to SSLRequest and GSSENCRequest. */
    public static ByteBuf refuseEncryption(ByteBufAllocator alloc) {
        return alloc.buffer(1).writeByte('N');
    }

    private static void field(ByteBuf buf, char code, String value) {
        buf.writeByte(code);
        buf.writeCharSequence(value, StandardCharsets.UTF_8);
        buf.writeByte(0);
    }
}
