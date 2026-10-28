package dev.queryguard.proxy.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PgFrontendDecoderTest {

    private static byte[] startup(String user, String db) {
        ByteBuf b = Unpooled.buffer();
        b.writeInt(0).writeInt(PgFrontendDecoder.PROTOCOL_3_0);
        for (String s : new String[] {"user", user, "database", db, "application_name", "psql"}) {
            b.writeCharSequence(s, StandardCharsets.UTF_8);
            b.writeByte(0);
        }
        b.writeByte(0);
        b.setInt(0, b.writerIndex());
        return bytes(b);
    }

    private static byte[] sslRequest() {
        return bytes(Unpooled.buffer().writeInt(8).writeInt(PgFrontendDecoder.SSL_REQUEST));
    }

    private static byte[] query(String sql) {
        return bytes(PgMessages.query(Unpooled.buffer().alloc(), sql));
    }

    private static byte[] bytes(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static List<FrontendMessage> feed(byte[] stream, int... cuts) {
        EmbeddedChannel ch = new EmbeddedChannel(new PgFrontendDecoder());
        int from = 0;
        for (int cut : cuts) {
            ch.writeInbound(Unpooled.wrappedBuffer(stream, from, cut - from));
            from = cut;
        }
        ch.writeInbound(Unpooled.wrappedBuffer(stream, from, stream.length - from));
        List<FrontendMessage> out = new ArrayList<>();
        FrontendMessage m;
        while ((m = ch.readInbound()) != null) {
            out.add(m);
        }
        out.forEach(x -> x.raw().release());
        ch.finishAndReleaseAll();
        return out;
    }

    @Test
    void sslRequestThenStartupThenQuery() {
        byte[] stream = concat(sslRequest(), startup("alice", "shop"), query("select 1"));
        List<FrontendMessage> msgs = feed(stream);
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0)).isInstanceOf(FrontendMessage.SslRequest.class);
        FrontendMessage.StartupMessage s = (FrontendMessage.StartupMessage) msgs.get(1);
        assertThat(s.params()).containsEntry("user", "alice").containsEntry("database", "shop");
        assertThat(((FrontendMessage.Query) msgs.get(2)).sql()).isEqualTo("select 1");
    }

    @Test
    void messageSplitAcrossReadsAtEveryPossibleByte() {
        byte[] stream = concat(startup("bob", "shop"), query("SELECT id FROM orders WHERE id = 1"));
        for (int cut = 1; cut < stream.length; cut++) {
            List<FrontendMessage> msgs = feed(stream, cut);
            assertThat(msgs).as("cut at %d", cut).hasSize(2);
            assertThat(((FrontendMessage.Query) msgs.get(1)).sql()).isEqualTo("SELECT id FROM orders WHERE id = 1");
        }
    }

    @Test
    void manyMessagesInOneRead() {
        byte[] stream = concat(startup("bob", "shop"), query("a"), query("b"), query("c"));
        assertThat(feed(stream)).hasSize(4);
    }

    @Test
    void extendedProtocolMessages() {
        ByteBuf parse = PgMessages.parse(Unpooled.buffer().alloc(), "s1", "select $1::int", new int[] {23});
        byte[] p = bytes(parse);
        // Bind: portal "", statement "s1", 0 format codes, 0 params, 0 result formats
        ByteBuf bind = Unpooled.buffer();
        bind.writeByte('B').writeInt(0).writeByte(0).writeBytes("s1".getBytes(StandardCharsets.UTF_8)).writeByte(0)
                .writeShort(0).writeShort(0).writeShort(0);
        bind.setInt(1, bind.writerIndex() - 1);
        ByteBuf exec = Unpooled.buffer().writeByte('E').writeInt(9).writeByte(0).writeInt(0);
        ByteBuf sync = Unpooled.buffer().writeByte('S').writeInt(4);
        List<FrontendMessage> msgs = feed(concat(startup("bob", "shop"), p, bytes(bind), bytes(exec), bytes(sync)), 7, 40);
        assertThat(msgs).hasSize(5);
        FrontendMessage.Parse parsed = (FrontendMessage.Parse) msgs.get(1);
        assertThat(parsed.name()).isEqualTo("s1");
        assertThat(parsed.sql()).isEqualTo("select $1::int");
        assertThat(parsed.paramTypes()).containsExactly(23);
        assertThat(((FrontendMessage.Bind) msgs.get(2)).statement()).isEqualTo("s1");
        assertThat(msgs.get(3)).isInstanceOf(FrontendMessage.Execute.class);
        assertThat(msgs.get(4)).isInstanceOf(FrontendMessage.Sync.class);
    }

    @Test
    void rawBytesAreExactlyWhatCameIn() {
        byte[] q = query("select 'é'");
        List<FrontendMessage> msgs;
        EmbeddedChannel ch = new EmbeddedChannel(new PgFrontendDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(concat(startup("a", "b"), q)));
        ch.<FrontendMessage>readInbound().raw().release();
        FrontendMessage.Query m = ch.readInbound();
        assertThat(bytes(m.raw())).isEqualTo(q);
        assertThat(m.sql()).isEqualTo("select 'é'");
        ch.finishAndReleaseAll();
    }
}
