package dev.queryguard.proxy.masking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.queryguard.common.masking.MaskStrategy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataRowMaskerTest {

    /** null in values means SQL NULL. */
    private static ByteBuf row(String... values) {
        ByteBuf b = Unpooled.buffer();
        b.writeByte('D').writeInt(0).writeShort(values.length);
        for (String v : values) {
            if (v == null) {
                b.writeInt(-1);
            } else {
                byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
                b.writeInt(bytes.length).writeBytes(bytes);
            }
        }
        b.setInt(1, b.writerIndex() - 1);
        return b;
    }

    private static List<String> read(ByteBuf b) {
        assertThat(b.getByte(0)).isEqualTo((byte) 'D');
        assertThat(b.getInt(1)).as("length field").isEqualTo(b.readableBytes() - 1);
        ByteBuf in = b.duplicate().skipBytes(5);
        int n = in.readUnsignedShort();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            out.add(len < 0 ? null : in.readCharSequence(len, StandardCharsets.UTF_8).toString());
        }
        assertThat(in.isReadable()).isFalse();
        return out;
    }

    @Test
    void masksOnlyTheMarkedColumnsAndFixesLengths() {
        ByteBuf raw = row("17", "user17@example.com", "+1-555-0117", "Customer 17");
        MaskStrategy[] masks = {null, MaskStrategy.PARTIAL_EMAIL, MaskStrategy.LAST4, null};
        ByteBuf out = DataRowMasker.mask(raw, masks, ByteBufAllocator.DEFAULT);
        assertThat(read(out)).containsExactly("17", "u***@***.com", "*******0117", "Customer 17");
        raw.release();
        out.release();
    }

    @Test
    void nullsStayNullAndNullifyMakesNulls() {
        ByteBuf raw = row(null, "x@y.com", "1234");
        ByteBuf out = DataRowMasker.mask(raw, new MaskStrategy[] {MaskStrategy.REDACT, MaskStrategy.NULLIFY, MaskStrategy.REDACT},
                ByteBufAllocator.DEFAULT);
        assertThat(read(out)).containsExactly(null, null, "****");
        raw.release();
        out.release();
    }

    @Test
    void multiByteUtf8() {
        ByteBuf raw = row("josé@exämple.de");
        ByteBuf out = DataRowMasker.mask(raw, new MaskStrategy[] {MaskStrategy.PARTIAL_EMAIL}, ByteBufAllocator.DEFAULT);
        assertThat(read(out)).containsExactly("j***@***.de");
        raw.release();
        out.release();
    }

    @Test
    void rowDescriptionParsing() {
        ByteBuf t = Unpooled.buffer();
        t.writeByte('T').writeInt(0).writeShort(2);
        for (Object[] f : new Object[][] {{"e", 16384L, 3}, {"upper", 0L, 0}}) {
            t.writeCharSequence((String) f[0], StandardCharsets.UTF_8);
            t.writeByte(0);
            t.writeInt((int) (long) (Long) f[1]).writeShort((Integer) f[2]).writeInt(25).writeShort(-1).writeInt(-1).writeShort(0);
        }
        t.setInt(1, t.writerIndex() - 1);
        var fields = RowDescription.parse(t);
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).tableOid()).isEqualTo(16384L);
        assertThat(fields.get(0).attnum()).isEqualTo(3);
        assertThat(fields.get(1).tableOid()).isZero();
        t.release();
    }
}
