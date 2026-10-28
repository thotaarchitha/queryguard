package dev.queryguard.proxy.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;

/**
 * Frames server bytes. The server always sends typed messages to us, because the proxy answers
 * SSLRequest itself and never forwards it (that's the one reply without a type byte).
 */
public final class PgBackendDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 5) {
            return;
        }
        int length = in.getInt(in.readerIndex() + 1);
        if (length < 4) {
            throw new CorruptedFrameException("bad backend message length " + length);
        }
        if (in.readableBytes() < length + 1) {
            return;
        }
        char type = (char) in.getByte(in.readerIndex());
        out.add(new BackendMessage(type, in.readRetainedSlice(length + 1)));
    }
}
