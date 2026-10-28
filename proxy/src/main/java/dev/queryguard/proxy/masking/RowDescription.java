package dev.queryguard.proxy.masking;

import dev.queryguard.proxy.protocol.PgFrontendDecoder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Decoded 'T' message: one entry per result column. */
public final class RowDescription {

    /**
     * {@code tableOid} and {@code attnum} are where Postgres says the column came from.
     * They're 0 for anything computed ({@code upper(email)}, {@code 1 + 1}), which is
     * exactly the case the masking guard blocks before the query runs.
     */
    public record Field(String name, long tableOid, int attnum, int typeOid, int format) {
    }

    private RowDescription() {
    }

    public static List<Field> parse(ByteBuf raw) {
        ByteBuf b = raw.duplicate().skipBytes(5);
        int n = b.readUnsignedShort();
        List<Field> fields = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = PgFrontendDecoder.readCString(b);
            long tableOid = b.readUnsignedInt();
            int attnum = b.readShort();
            int typeOid = b.readInt();
            b.skipBytes(2 + 4); // typlen, typmod
            int format = b.readShort();
            fields.add(new Field(name, tableOid, attnum, typeOid, format));
        }
        return fields;
    }
}
