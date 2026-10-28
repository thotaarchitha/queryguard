package dev.queryguard.proxy.masking;

import dev.queryguard.common.masking.MaskStrategy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.charset.StandardCharsets;

/**
 * Rewrites one 'D' message.
 *
 * <pre>
 * 'D' | int32 length | int16 columns | (int32 len | bytes)*    len -1 = NULL
 * </pre>
 *
 * Masked values change length, so every column length after them shifts and the message
 * length has to be recomputed. Unmasked columns are copied as bytes, never decoded.
 */
public final class DataRowMasker {

    private DataRowMasker() {
    }

    /** Returns a new buffer; the caller still owns (and must release) {@code raw}. */
    public static ByteBuf mask(ByteBuf raw, MaskStrategy[] masks, ByteBufAllocator alloc) {
        ByteBuf in = raw.duplicate().skipBytes(5);
        int columns = in.readUnsignedShort();
        ByteBuf out = alloc.buffer(raw.readableBytes() + 16 * masks.length);
        out.writeByte('D');
        out.writeInt(0);
        out.writeShort(columns);
        for (int i = 0; i < columns; i++) {
            int len = in.readInt();
            if (len < 0) {
                out.writeInt(-1);
                continue;
            }
            MaskStrategy strategy = i < masks.length ? masks[i] : null;
            if (strategy == null) {
                out.writeInt(len);
                out.writeBytes(in, len);
                continue;
            }
            if (strategy == MaskStrategy.NULLIFY) {
                in.skipBytes(len);
                out.writeInt(-1);
                continue;
            }
            String value = in.readCharSequence(len, StandardCharsets.UTF_8).toString();
            String masked = strategy.apply(value);
            if (masked == null) {
                out.writeInt(-1);
            } else {
                int lenAt = out.writerIndex();
                out.writeInt(0);
                int written = out.writeCharSequence(masked, StandardCharsets.UTF_8);
                out.setInt(lenAt, written);
            }
        }
        out.setInt(1, out.writerIndex() - 1);
        return out;
    }
}
